package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A handle to an acquired lock with explicit state management and auto-renewal.
 *
 * <p>Implements {@link AutoCloseable} for try-with-resources. Tracks lock state
 * as {@link State}: {@code HELD → LOST → RELEASED}.</p>
 *
 * <p>When auto-renewal fails (e.g., another client acquired the lock after expiry),
 * the state transitions to {@code LOST}, renewal stops, and an optional callback
 * is invoked to notify the caller.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (LockHandle handle = lockClient.acquireWithAutoRenew(request)) {
 *     handle.onLockLost(key -> log.error("Lost lock for {}", key));
 *     while (handle.isHeld()) {
 *         // do work — check isHeld() periodically
 *     }
 * }
 * }</pre>
 */
@Slf4j
public class LockHandle implements AutoCloseable {

    public enum State { HELD, LOST, RELEASED }

    private final LockResult lockResult;
    private final LockEngine engine;
    private final LockRegistry registry;
    private final LockMetrics metrics;
    private final AtomicReference<State> state = new AtomicReference<>(State.HELD);
    private volatile ScheduledFuture<?> renewalTask;
    private volatile Consumer<String> lostCallback;

    public LockHandle(LockResult lockResult, LockEngine engine, LockRegistry registry, LockMetrics metrics) {
        this.lockResult = lockResult;
        this.engine = engine;
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * Registers a callback invoked when the lock is lost (renewal failed).
     * The callback receives the resource key.
     */
    public void onLockLost(Consumer<String> callback) {
        this.lostCallback = callback;
    }

    /**
     * Starts auto-renewal of the lock at the specified interval.
     * Stops automatically if renewal fails (state transitions to LOST).
     */
    public void startAutoRenewal(long renewIntervalMs, long leaseMs, ScheduledExecutorService scheduler) {
        this.renewalTask = scheduler.scheduleAtFixedRate(() -> {
            if (state.get() != State.HELD) {
                return;
            }
            try {
                boolean renewed = engine.renew(lockResult.getResourceKey(), lockResult.getLockToken(), leaseMs);
                if (!renewed) {
                    if (state.compareAndSet(State.HELD, State.LOST)) {
                        log.warn("Lock LOST: renewal failed for resource_key={} — stopping auto-renewal",
                                lockResult.getResourceKey());
                        metrics.recordLockLost();
                        cancelRenewalTask();
                        notifyLockLost();
                    }
                }
            } catch (Exception e) {
                if (state.compareAndSet(State.HELD, State.LOST)) {
                    log.error("Lock LOST: renewal error for resource_key={}: {}",
                            lockResult.getResourceKey(), e.getMessage());
                    metrics.recordLockLost();
                    cancelRenewalTask();
                    notifyLockLost();
                }
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
    }

    public LockResult getLockResult() {
        return lockResult;
    }

    public String getResourceKey() {
        return lockResult.getResourceKey();
    }

    public String getLockToken() {
        return lockResult.getLockToken();
    }

    public long getFencingToken() {
        return lockResult.getFencingToken();
    }

    public State getState() {
        return state.get();
    }

    /**
     * Returns true only if the lock is actively held (not lost or released).
     */
    public boolean isHeld() {
        return state.get() == State.HELD;
    }

    /**
     * @deprecated Use {@link #isHeld()} instead. This method exists for backward compatibility.
     */
    @Deprecated
    public boolean isAcquired() {
        return isHeld();
    }

    /**
     * Releases the lock and stops auto-renewal.
     */
    @Override
    public void close() {
        State previous = state.getAndSet(State.RELEASED);
        if (previous == State.RELEASED) {
            return; // already closed
        }

        cancelRenewalTask();
        try {
            if (previous == State.HELD) {
                // Only attempt release if we still think we hold it
                boolean success = engine.release(lockResult.getResourceKey(), lockResult.getLockToken());
                if (!success) {
                    log.warn("Release failed on close for resource_key={} (may have expired)",
                            lockResult.getResourceKey());
                }
            }
            // If previous == LOST, skip release (we don't own it anymore)
        } finally {
            registry.unregister(this);
        }
    }

    private void cancelRenewalTask() {
        if (renewalTask != null) {
            renewalTask.cancel(false);
        }
    }

    private void notifyLockLost() {
        Consumer<String> callback = this.lostCallback;
        if (callback != null) {
            try {
                callback.accept(lockResult.getResourceKey());
            } catch (Exception e) {
                log.error("Lock-lost callback threw exception for resource_key={}: {}",
                        lockResult.getResourceKey(), e.getMessage());
            }
        }
    }
}
