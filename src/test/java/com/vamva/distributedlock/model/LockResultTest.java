package com.vamva.distributedlock.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LockResultTest {

    @Test
    void acquiredResultHasCorrectFields() {
        LockResult result = LockResult.acquired("res:1", "token-1", 30_000, 99999L, 42);

        assertTrue(result.isAcquired());
        assertEquals(AcquireOutcome.ACQUIRED, result.getOutcome());
        assertTrue(result.isVerifiedOwnership());
        assertEquals("res:1", result.getResourceKey());
        assertEquals("token-1", result.getLockToken());
        assertEquals(30_000, result.getLeaseMs());
        assertEquals(99999L, result.getExpiresAt());
        assertEquals(42, result.getFencingToken());
    }

    @Test
    void contendedResultHasCorrectFields() {
        LockResult result = LockResult.contended("res:2");

        assertFalse(result.isAcquired());
        assertEquals(AcquireOutcome.CONTENDED, result.getOutcome());
        assertFalse(result.isVerifiedOwnership());
        assertEquals("res:2", result.getResourceKey());
        assertNull(result.getLockToken());
        assertEquals(0, result.getFencingToken());
    }

    @Test
    void timeoutResultHasCorrectFields() {
        LockResult result = LockResult.timeout("res:3");

        assertFalse(result.isAcquired());
        assertEquals(AcquireOutcome.TIMEOUT, result.getOutcome());
        assertFalse(result.isVerifiedOwnership());
    }

    @Test
    void backendUnavailableResultHasCorrectFields() {
        LockResult result = LockResult.backendUnavailable("res:4");

        assertFalse(result.isAcquired());
        assertEquals(AcquireOutcome.BACKEND_UNAVAILABLE, result.getOutcome());
        assertFalse(result.isVerifiedOwnership());
    }

    @Test
    void failOpenSyntheticResultHasCorrectFields() {
        LockResult result = LockResult.failOpenSynthetic("res:5", "token-5", 10_000, 88888L);

        assertTrue(result.isAcquired());
        assertEquals(AcquireOutcome.FAIL_OPEN_SYNTHETIC, result.getOutcome());
        assertFalse(result.isVerifiedOwnership(), "Fail-open should NOT be verified ownership");
        assertEquals("res:5", result.getResourceKey());
        assertEquals("token-5", result.getLockToken());
        assertEquals(0, result.getFencingToken(), "Fail-open should have fence=0");
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedSuccessFactoryStillWorks() {
        LockResult result = LockResult.success("res:6", "token-6", 30_000, 99999L, 7);

        assertTrue(result.isAcquired());
        assertEquals(AcquireOutcome.ACQUIRED, result.getOutcome());
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedFailureFactoryStillWorks() {
        LockResult result = LockResult.failure("res:7");

        assertFalse(result.isAcquired());
        assertEquals(AcquireOutcome.CONTENDED, result.getOutcome());
    }
}
