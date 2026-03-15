package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.model.LockResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A handle to an acquired lock that supports auto-renewal and safe release.
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources blocks.
 * When auto-renewal is enabled, a background thread periodically renews the lease
 * at a configurable interval (default: 2/3 of lease duration).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (LockHandle handle = lockClient.acquireWithAutoRenew(request)) {
 *     // do long-running work — lease is automatically renewed
 * }
 * // lock is released automatically on close
 * }</pre>
 */
@Slf4j
public class LockHandle implements AutoCloseable {

    private final LockResult lockResult;
    private final LockEngine engine;
    private final LockRegistry registry;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> renewalTask;

    public LockHandle(LockResult lockResult, LockEngine engine, LockRegistry registry) {
        this.lockResult = lockResult;
        this.engine = engine;
        this.registry = registry;
    }

    /**
     * Starts auto-renewal of the lock at the specified interval.
     *
     * @param renewIntervalMs  the interval between renewal attempts
     * @param leaseMs          the lease duration to renew with
     * @param scheduler        the scheduler to use for background renewal
     */
    public void startAutoRenewal(long renewIntervalMs, long leaseMs, ScheduledExecutorService scheduler) {
        this.renewalTask = scheduler.scheduleAtFixedRate(() -> {
            if (released.get()) {
                return;
            }
            try {
                boolean renewed = engine.renew(lockResult.getResourceKey(), lockResult.getLockToken(), leaseMs);
                if (!renewed) {
                    log.warn("Auto-renewal failed for resource_key={} — lock may have expired",
                            lockResult.getResourceKey());
                }
            } catch (Exception e) {
                log.error("Auto-renewal error for resource_key={}: {}",
                        lockResult.getResourceKey(), e.getMessage());
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

    public boolean isAcquired() {
        return lockResult.isAcquired() && !released.get();
    }

    /**
     * Releases the lock and stops auto-renewal if active.
     */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            if (renewalTask != null) {
                renewalTask.cancel(false);
            }
            try {
                boolean success = engine.release(lockResult.getResourceKey(), lockResult.getLockToken());
                if (!success) {
                    log.warn("Release failed on close for resource_key={} (may have expired)",
                            lockResult.getResourceKey());
                }
            } finally {
                registry.unregister(this);
            }
        }
    }
}
