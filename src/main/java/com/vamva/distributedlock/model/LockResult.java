package com.vamva.distributedlock.model;

import lombok.Getter;

/**
 * Immutable result of a lock acquisition attempt.
 *
 * <p>Contains the {@link AcquireOutcome} that distinguishes between successful
 * acquisition, contention, backend failure, timeout, and fail-open synthetic success.
 * Includes a monotonically increasing fencing token for stale owner protection.</p>
 */
@Getter
public class LockResult {

    private final boolean acquired;
    private final AcquireOutcome outcome;
    private final String resourceKey;
    private final String lockToken;
    private final long leaseMs;
    private final long expiresAt;
    private final long fencingToken;

    public LockResult(boolean acquired, AcquireOutcome outcome, String resourceKey,
                      String lockToken, long leaseMs, long expiresAt, long fencingToken) {
        this.acquired = acquired;
        this.outcome = outcome;
        this.resourceKey = resourceKey;
        this.lockToken = lockToken;
        this.leaseMs = leaseMs;
        this.expiresAt = expiresAt;
        this.fencingToken = fencingToken;
    }

    public static LockResult acquired(String resourceKey, String lockToken,
                                      long leaseMs, long expiresAt, long fencingToken) {
        return new LockResult(true, AcquireOutcome.ACQUIRED, resourceKey,
                lockToken, leaseMs, expiresAt, fencingToken);
    }

    public static LockResult failOpenSynthetic(String resourceKey, String lockToken,
                                                long leaseMs, long expiresAt) {
        return new LockResult(true, AcquireOutcome.FAIL_OPEN_SYNTHETIC, resourceKey,
                lockToken, leaseMs, expiresAt, 0);
    }

    public static LockResult contended(String resourceKey) {
        return new LockResult(false, AcquireOutcome.CONTENDED, resourceKey, null, 0, 0, 0);
    }

    public static LockResult timeout(String resourceKey) {
        return new LockResult(false, AcquireOutcome.TIMEOUT, resourceKey, null, 0, 0, 0);
    }

    public static LockResult backendUnavailable(String resourceKey) {
        return new LockResult(false, AcquireOutcome.BACKEND_UNAVAILABLE, resourceKey, null, 0, 0, 0);
    }

    /** Whether ownership is verified in the backend (not fail-open synthetic). */
    public boolean isVerifiedOwnership() {
        return outcome == AcquireOutcome.ACQUIRED;
    }

    // Backward compatibility
    @Deprecated
    public static LockResult success(String resourceKey, String lockToken,
                                     long leaseMs, long expiresAt, long fencingToken) {
        return acquired(resourceKey, lockToken, leaseMs, expiresAt, fencingToken);
    }

    @Deprecated
    public static LockResult failure(String resourceKey) {
        return contended(resourceKey);
    }
}
