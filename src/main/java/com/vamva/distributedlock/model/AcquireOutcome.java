package com.vamva.distributedlock.model;

/**
 * Outcome of a lock acquisition attempt.
 *
 * <p>Distinguishes between different failure modes so callers and operators
 * can react appropriately to each scenario.</p>
 */
public enum AcquireOutcome {

    /** Lock acquired successfully with verified ownership. */
    ACQUIRED,

    /** Lock is held by another client. */
    CONTENDED,

    /** Acquisition timed out after exhausting retry attempts. */
    TIMEOUT,

    /** Backend (Redis) is unavailable and fail-closed prevented acquisition. */
    BACKEND_UNAVAILABLE,

    /**
     * Backend unavailable but fail-open returned synthetic success.
     * The lock is NOT verified in the backend. Fencing token is 0.
     * Caller should treat this as degraded mode.
     */
    FAIL_OPEN_SYNTHETIC
}
