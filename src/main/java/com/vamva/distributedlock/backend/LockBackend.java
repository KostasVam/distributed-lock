package com.vamva.distributedlock.backend;

/**
 * Storage backend for distributed lock operations.
 *
 * <p>This interface provides <strong>best-effort mutual exclusion</strong> under a
 * lease-based model. It does not provide linearizable guarantees in the presence of
 * network partitions, process pauses, or backend failovers.</p>
 *
 * <p><strong>Lock lifecycle:</strong></p>
 * <pre>
 * FREE ──acquire──▶ HELD ──release──▶ FREE
 *                    │
 *                    ├── renew ──▶ HELD (lease extended)
 *                    │
 *                    └── lease expiry ──▶ FREE
 * </pre>
 *
 * <p>All operations are <strong>ownership-safe</strong>: release and renew verify the
 * caller's token before proceeding, and safely return {@code false} without side effects
 * when the token does not match (idempotent failure).</p>
 *
 * <p>Two implementations are provided: {@link RedisLockBackend} for distributed
 * deployments and {@link InMemoryLockBackend} for local/dev/test use.</p>
 */
public interface LockBackend {

    /**
     * Attempts to acquire a lock atomically, returning a fencing token on success.
     *
     * <p>The fencing token is a monotonically increasing value that downstream systems
     * can use to reject stale writes from expired lock holders.</p>
     *
     * @param key     the fully-qualified lock key (e.g., {@code lock:job:daily-settlement})
     * @param token   the unique owner token
     * @param leaseMs the lease duration in milliseconds
     * @return the fencing token (positive) if acquired, {@code -1} if already held
     */
    long acquire(String key, String token, long leaseMs);

    /**
     * Releases a lock atomically, only if the caller owns it.
     *
     * @param key   the lock key
     * @param token the owner token that must match the stored value
     * @return {@code true} if the lock was released, {@code false} if not owned or missing
     */
    boolean release(String key, String token);

    /**
     * Renews a lock's lease atomically, only if the caller owns it.
     *
     * @param key     the lock key
     * @param token   the owner token that must match the stored value
     * @param leaseMs the new lease duration in milliseconds
     * @return {@code true} if the lease was extended, {@code false} if not owned or missing
     */
    boolean renew(String key, String token, long leaseMs);
}
