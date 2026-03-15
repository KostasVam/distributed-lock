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
        long fence = backend.acquire("lock:test:1", "token-1", 30_000);
        assertTrue(fence >= 0, "Free lock should be acquired");
    }

    @Test
    void acquireReturnsFencingToken() {
        long fence1 = backend.acquire("lock:test:fence:1", "token-1", 30_000);
        assertTrue(fence1 > 0, "First fencing token should be positive");

        backend.release("lock:test:fence:1", "token-1");
        long fence2 = backend.acquire("lock:test:fence:1", "token-2", 30_000);
        assertTrue(fence2 > fence1, "Fencing token should be monotonically increasing");
    }

    @Test
    void secondClientCannotAcquireHeldLock() {
        backend.acquire("lock:test:2", "token-1", 30_000);

        long fence = backend.acquire("lock:test:2", "token-2", 30_000);
        assertEquals(-1L, fence, "Second client should not acquire held lock");
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
        long baseMs = 1_000_000_000L;
        Clock baseClock = Clock.fixed(Instant.ofEpochMilli(baseMs), ZoneOffset.UTC);
        InMemoryLockBackend timedBackend = new InMemoryLockBackend(baseClock);

        timedBackend.acquire("lock:test:7", "token-1", 100);

        long blocked = timedBackend.acquire("lock:test:7", "token-2", 30_000);
        assertEquals(-1L, blocked, "Lock should still be held before expiration");

        InMemoryLockBackend backend2 = new InMemoryLockBackend();
        backend2.acquire("lock:test:7b", "token-1", 50);
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        long acquired = backend2.acquire("lock:test:7b", "token-2", 30_000);
        assertTrue(acquired >= 0, "Expired lock should be available for new client");
    }

    @Test
    void lockCanBeReacquiredAfterRelease() {
        backend.acquire("lock:test:8", "token-1", 30_000);
        backend.release("lock:test:8", "token-1");

        long fence = backend.acquire("lock:test:8", "token-2", 30_000);
        assertTrue(fence >= 0, "Released lock should be available");
    }

    @Test
    void differentKeysAreIndependent() {
        long a = backend.acquire("lock:test:a", "token-1", 30_000);
        long b = backend.acquire("lock:test:b", "token-2", 30_000);

        assertTrue(a >= 0, "Lock A should be acquired");
        assertTrue(b >= 0, "Lock B should be acquired independently");
    }

    @Test
    void renewExtendsLease() {
        InMemoryLockBackend backend2 = new InMemoryLockBackend();
        backend2.acquire("lock:test:9", "token-1", 5_000);

        boolean renewed = backend2.renew("lock:test:9", "token-1", 30_000);
        assertTrue(renewed, "Should renew before expiry");

        long blocked = backend2.acquire("lock:test:9", "token-2", 30_000);
        assertEquals(-1L, blocked, "Lock should still be held after renewal");
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
