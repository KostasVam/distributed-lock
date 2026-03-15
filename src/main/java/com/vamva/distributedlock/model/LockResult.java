package com.vamva.distributedlock.model;

import lombok.Getter;

/**
 * Immutable result of a lock operation (acquire, renew, release).
 *
 * <p>Contains the outcome along with metadata for logging and diagnostics.</p>
 */
@Getter
public class LockResult {

    private final boolean acquired;
    private final String resourceKey;
    private final String lockToken;
    private final long leaseMs;
    private final long expiresAt;

    public LockResult(boolean acquired, String resourceKey, String lockToken,
                      long leaseMs, long expiresAt) {
        this.acquired = acquired;
        this.resourceKey = resourceKey;
        this.lockToken = lockToken;
        this.leaseMs = leaseMs;
        this.expiresAt = expiresAt;
    }

    public static LockResult success(String resourceKey, String lockToken,
                                     long leaseMs, long expiresAt) {
        return new LockResult(true, resourceKey, lockToken, leaseMs, expiresAt);
    }

    public static LockResult failure(String resourceKey) {
        return new LockResult(false, resourceKey, null, 0, 0);
    }
}
