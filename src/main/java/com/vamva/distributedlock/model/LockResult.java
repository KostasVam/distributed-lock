package com.vamva.distributedlock.model;

import lombok.Getter;

/**
 * Immutable result of a lock operation (acquire, renew, release).
 *
 * <p>Contains the outcome along with metadata for logging and diagnostics.
 * Includes a monotonically increasing fencing token that downstream systems
 * can use to reject stale writes from expired lock holders.</p>
 */
@Getter
public class LockResult {

    private final boolean acquired;
    private final String resourceKey;
    private final String lockToken;
    private final long leaseMs;
    private final long expiresAt;
    private final long fencingToken;

    public LockResult(boolean acquired, String resourceKey, String lockToken,
                      long leaseMs, long expiresAt, long fencingToken) {
        this.acquired = acquired;
        this.resourceKey = resourceKey;
        this.lockToken = lockToken;
        this.leaseMs = leaseMs;
        this.expiresAt = expiresAt;
        this.fencingToken = fencingToken;
    }

    public static LockResult success(String resourceKey, String lockToken,
                                     long leaseMs, long expiresAt, long fencingToken) {
        return new LockResult(true, resourceKey, lockToken, leaseMs, expiresAt, fencingToken);
    }

    public static LockResult failure(String resourceKey) {
        return new LockResult(false, resourceKey, null, 0, 0, 0);
    }
}
