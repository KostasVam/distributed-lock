package com.vamva.distributedlock.api;

import com.vamva.distributedlock.engine.LockEngine;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * Public API for the distributed lock library.
 *
 * <p>This is the primary interface used by applications. It provides:</p>
 * <ul>
 *   <li>{@link #tryAcquire(LockRequest)} — single-attempt lock acquisition</li>
 *   <li>{@link #acquire(LockRequest)} — acquire with retry and timeout</li>
 *   <li>{@link #renew(String, String, long)} — extend lock lease</li>
 *   <li>{@link #release(String, String)} — release lock</li>
 *   <li>{@link #executeWithLock(LockRequest, Callable)} — acquire, execute, release pattern</li>
 * </ul>
 */
@Slf4j
public class DistributedLockClient {

    private final LockEngine engine;

    public DistributedLockClient(LockEngine engine) {
        this.engine = engine;
    }

    /**
     * Attempts to acquire a lock once. Returns immediately.
     *
     * @param request the lock request
     * @return the lock result (check {@link LockResult#isAcquired()})
     */
    public LockResult tryAcquire(LockRequest request) {
        return engine.tryAcquire(request);
    }

    /**
     * Acquires a lock, retrying with exponential backoff until the wait timeout.
     *
     * @param request the lock request (must have positive waitTimeoutMs for retry behavior)
     * @return the lock result
     * @throws InterruptedException if interrupted while waiting
     */
    public LockResult acquire(LockRequest request) throws InterruptedException {
        return engine.acquire(request);
    }

    /**
     * Renews an acquired lock's lease.
     *
     * @param resourceKey the resource key
     * @param token       the lock token from acquisition
     * @param leaseMs     the new lease duration in milliseconds
     * @return {@code true} if the lease was extended
     */
    public boolean renew(String resourceKey, String token, long leaseMs) {
        return engine.renew(resourceKey, token, leaseMs);
    }

    /**
     * Releases an acquired lock.
     *
     * @param resourceKey the resource key
     * @param token       the lock token from acquisition
     * @return {@code true} if the lock was released
     */
    public boolean release(String resourceKey, String token) {
        return engine.release(resourceKey, token);
    }

    /**
     * Acquires a lock, executes the task, and releases the lock in a finally block.
     *
     * <p>This is the recommended pattern for most use cases. It ensures the lock is
     * always released, even if the task throws an exception.</p>
     *
     * @param request the lock request
     * @param task    the task to execute while holding the lock
     * @param <T>     the return type
     * @return the task result
     * @throws Exception if lock acquisition fails or the task throws
     */
    public <T> T executeWithLock(LockRequest request, Callable<T> task) throws Exception {
        LockResult lockResult = engine.acquire(request);

        if (!lockResult.isAcquired()) {
            throw new LockAcquisitionException(
                    "Failed to acquire lock for resource: " + request.getResourceKey());
        }

        try {
            return task.call();
        } finally {
            boolean released = engine.release(lockResult.getResourceKey(), lockResult.getLockToken());
            if (!released) {
                log.warn("Failed to release lock resource_key={} token={} (may have expired)",
                        lockResult.getResourceKey(), lockResult.getLockToken());
            }
        }
    }

    /**
     * Acquires a lock, executes the task, and releases the lock in a finally block.
     *
     * @param request  the lock request
     * @param runnable the task to execute while holding the lock
     * @throws Exception if lock acquisition fails or the task throws
     */
    public void executeWithLock(LockRequest request, Runnable runnable) throws Exception {
        executeWithLock(request, () -> {
            runnable.run();
            return null;
        });
    }
}
