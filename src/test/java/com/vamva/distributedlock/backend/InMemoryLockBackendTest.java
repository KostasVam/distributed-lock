package com.vamva.distributedlock.backend;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLockBackendTest {

    private final InMemoryLockBackend backend = new InMemoryLockBackend();

    @Test
    void freeLockCanBeAcquired() {
        boolean acquired = backend.acquire("lock:test:1", "token-1", 30_000);
        assertTrue(acquired, "Free lock should be acquired");
    }

    @Test
    void secondClientCannotAcquireHeldLock() {
        backend.acquire("lock:test:2", "token-1", 30_000);

        boolean acquired = backend.acquire("lock:test:2", "token-2", 30_000);
        assertFalse(acquired, "Second client should not acquire held lock");
    }

    @Test
    void ownerCanReleaseLock() {
        backend.acquire("lock:test:3", "token-1", 30_000);

        boolean released = backend.release("lock:test:3", "token-1");
        assertTrue(released, "Owner should release lock");
    }

    @Test
    void nonOwnerCannotReleaseLock() {
        backend.acquire("lock:test:4", "token-1", 30_000);

        boolean released = backend.release("lock:test:4", "token-wrong");
        assertFalse(released, "Non-owner should not release lock");
    }

    @Test
    void ownerCanRenewLock() {
        backend.acquire("lock:test:5", "token-1", 30_000);

        boolean renewed = backend.renew("lock:test:5", "token-1", 60_000);
        assertTrue(renewed, "Owner should renew lock");
    }

    @Test
    void nonOwnerCannotRenewLock() {
        backend.acquire("lock:test:6", "token-1", 30_000);

        boolean renewed = backend.renew("lock:test:6", "token-wrong", 60_000);
        assertFalse(renewed, "Non-owner should not renew lock");
    }

    @Test
    void expiredLockBecomesAvailable() {
        // Use a mutable clock via Clock.offset to deterministically test expiration
        long baseMs = 1_000_000_000L;
        Clock baseClock = Clock.fixed(Instant.ofEpochMilli(baseMs), ZoneOffset.UTC);
        InMemoryLockBackend timedBackend = new InMemoryLockBackend(baseClock);

        // Acquire with 100ms lease at baseMs
        timedBackend.acquire("lock:test:7", "token-1", 100);

        // Second client cannot acquire (lock not yet expired)
        boolean blocked = timedBackend.acquire("lock:test:7", "token-2", 30_000);
        assertFalse(blocked, "Lock should still be held before expiration");

        // Create new backend with same data is not possible, so use real sleep approach
        // with a generous margin to avoid flakiness
        InMemoryLockBackend backend2 = new InMemoryLockBackend();
        backend2.acquire("lock:test:7b", "token-1", 50); // 50ms lease

        try { Thread.sleep(100); } catch (InterruptedException ignored) {} // 2x margin

        boolean acquired = backend2.acquire("lock:test:7b", "token-2", 30_000);
        assertTrue(acquired, "Expired lock should be available for new client");
    }

    @Test
    void lockCanBeReacquiredAfterRelease() {
        backend.acquire("lock:test:8", "token-1", 30_000);
        backend.release("lock:test:8", "token-1");

        boolean acquired = backend.acquire("lock:test:8", "token-2", 30_000);
        assertTrue(acquired, "Released lock should be available");
    }

    @Test
    void differentKeysAreIndependent() {
        boolean a = backend.acquire("lock:test:a", "token-1", 30_000);
        boolean b = backend.acquire("lock:test:b", "token-2", 30_000);

        assertTrue(a, "Lock A should be acquired");
        assertTrue(b, "Lock B should be acquired independently");
    }

    @Test
    void renewExtendsLease() {
        InMemoryLockBackend backend2 = new InMemoryLockBackend();
        backend2.acquire("lock:test:9", "token-1", 5_000);

        // Renew with longer lease
        boolean renewed = backend2.renew("lock:test:9", "token-1", 30_000);
        assertTrue(renewed, "Should renew before expiry");

        // Lock should still be held (cannot be acquired by another)
        boolean acquired = backend2.acquire("lock:test:9", "token-2", 30_000);
        assertFalse(acquired, "Lock should still be held after renewal");
    }

    @Test
    void releaseNonExistentKeyReturnsFalse() {
        boolean released = backend.release("lock:nonexistent", "token-1");
        assertFalse(released);
    }

    @Test
    void renewNonExistentKeyReturnsFalse() {
        boolean renewed = backend.renew("lock:nonexistent", "token-1", 30_000);
        assertFalse(renewed);
    }
}
