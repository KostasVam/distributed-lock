package com.vamva.distributedlock.api;

/**
 * Thrown when lock acquisition fails in {@link DistributedLockClient#executeWithLock}.
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }
}
