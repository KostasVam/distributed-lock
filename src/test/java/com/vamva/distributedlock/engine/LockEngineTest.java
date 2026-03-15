package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import com.vamva.distributedlock.token.TokenGenerator;
import com.vamva.distributedlock.token.UuidTokenGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class LockEngineTest {

    private LockEngine engine;
    private InMemoryLockBackend backend;
    private DistributedLockProperties properties;

    @BeforeEach
    void setUp() {
        backend = new InMemoryLockBackend();
        TokenGenerator tokenGenerator = new UuidTokenGenerator();
        properties = new DistributedLockProperties();
        properties.setBackend("in-memory");
        properties.setDefaultLeaseMs(30_000);
        properties.setOwnerId("test-instance");
        LockMetrics metrics = new LockMetrics(new SimpleMeterRegistry(), "in-memory");
        engine = new LockEngine(backend, tokenGenerator, metrics, Clock.systemUTC(),
                properties, ObservationRegistry.NOOP);
    }

    @Test
    void tryAcquireSucceedsOnFreeLock() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-1")
                .leaseMs(30_000)
                .build();

        LockResult result = engine.tryAcquire(request);

        assertTrue(result.isAcquired());
        assertEquals("job:test-1", result.getResourceKey());
        assertNotNull(result.getLockToken());
        assertTrue(result.getExpiresAt() > System.currentTimeMillis());
    }

    @Test
    void tryAcquireFailsOnHeldLock() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-2")
                .leaseMs(30_000)
                .build();

        engine.tryAcquire(request);
        LockResult result = engine.tryAcquire(request);

        assertFalse(result.isAcquired());
        assertNull(result.getLockToken());
    }

    @Test
    void releaseAllowsReacquisition() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-3")
                .leaseMs(30_000)
                .build();

        LockResult first = engine.tryAcquire(request);
        assertTrue(first.isAcquired());

        boolean released = engine.release(first.getResourceKey(), first.getLockToken());
        assertTrue(released);

        LockResult second = engine.tryAcquire(request);
        assertTrue(second.isAcquired());
    }

    @Test
    void renewExtendLease() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-4")
                .leaseMs(30_000)
                .build();

        LockResult result = engine.tryAcquire(request);
        assertTrue(result.isAcquired());

        boolean renewed = engine.renew(result.getResourceKey(), result.getLockToken(), 60_000);
        assertTrue(renewed);
    }

    @Test
    void renewFailsWithWrongToken() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-5")
                .leaseMs(30_000)
                .build();

        engine.tryAcquire(request);

        boolean renewed = engine.renew("job:test-5", "wrong-token", 60_000);
        assertFalse(renewed);
    }

    @Test
    void releaseFailsWithWrongToken() {
        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-6")
                .leaseMs(30_000)
                .build();

        engine.tryAcquire(request);

        boolean released = engine.release("job:test-6", "wrong-token");
        assertFalse(released);
    }

    @Test
    void acquireWithTimeoutSucceedsWhenLockFreed() throws InterruptedException {
        LockRequest holdRequest = LockRequest.builder()
                .resourceKey("job:test-7")
                .leaseMs(100) // very short lease
                .build();

        LockResult held = engine.tryAcquire(holdRequest);
        assertTrue(held.isAcquired());

        // Release after a short delay in a separate thread
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            engine.release(held.getResourceKey(), held.getLockToken());
        }).start();

        LockRequest waitRequest = LockRequest.builder()
                .resourceKey("job:test-7")
                .leaseMs(30_000)
                .waitTimeoutMs(2000)
                .build();

        LockResult result = engine.acquire(waitRequest);
        assertTrue(result.isAcquired());
    }

    @Test
    void acquireWithTimeoutFailsWhenLockNeverFreed() throws InterruptedException {
        LockRequest holdRequest = LockRequest.builder()
                .resourceKey("job:test-8")
                .leaseMs(60_000) // long lease
                .build();

        engine.tryAcquire(holdRequest);

        LockRequest waitRequest = LockRequest.builder()
                .resourceKey("job:test-8")
                .leaseMs(30_000)
                .waitTimeoutMs(200) // short timeout
                .build();

        LockResult result = engine.acquire(waitRequest);
        assertFalse(result.isAcquired());
    }

    @Test
    void calculateBackoffReturnsPositiveValues() {
        for (int i = 0; i < 20; i++) {
            long backoff = engine.calculateBackoff(i);
            assertTrue(backoff > 0, "Backoff should be positive for attempt " + i);
            assertTrue(backoff <= properties.getRetry().getMaxBackoffMs(),
                    "Backoff should not exceed configured max");
        }
    }

    @Test
    void defaultLeaseMsUsedWhenRequestHasZero() {
        properties.setDefaultLeaseMs(15_000);

        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-default-lease")
                .build(); // leaseMs defaults to 0

        LockResult result = engine.tryAcquire(request);

        assertTrue(result.isAcquired());
        assertEquals(15_000, result.getLeaseMs(), "Should use default lease from properties");
    }

    @Test
    void globalOwnerIdUsedWhenRequestHasNone() {
        // This test verifies that the engine doesn't throw and logs work.
        // The ownerId appears in logs, not in the result.
        properties.setOwnerId("global-owner-1");

        LockRequest request = LockRequest.builder()
                .resourceKey("job:test-owner-fallback")
                .leaseMs(30_000)
                .build();

        LockResult result = engine.tryAcquire(request);
        assertTrue(result.isAcquired());
    }

    @Test
    void hashResourceKeyProducesDeterministicHash() {
        String hash1 = LockEngine.hashResourceKey("job:daily-settlement");
        String hash2 = LockEngine.hashResourceKey("job:daily-settlement");
        String hash3 = LockEngine.hashResourceKey("job:other-key");

        assertEquals(hash1, hash2, "Same key should produce same hash");
        assertNotEquals(hash1, hash3, "Different keys should produce different hashes");
        assertEquals(16, hash1.length(), "Hash should be 16 hex characters");
    }

    @Test
    void calculateBackoffRespectsConfiguredValues() {
        properties.getRetry().setInitialBackoffMs(100);
        properties.getRetry().setMaxBackoffMs(500);
        properties.getRetry().setJitter(false);

        long backoff0 = engine.calculateBackoff(0);
        // With no jitter, first attempt = min(100 * 2^0, 500) = 100
        // Actually attempt is 1-indexed in usage, but 0 works fine here
        assertTrue(backoff0 >= 100 && backoff0 <= 500);
    }

    @Test
    void retryDisabledForceSingleAttemptEvenWithTimeout() throws InterruptedException {
        properties.getRetry().setEnabled(false);

        // Hold a lock
        engine.tryAcquire(LockRequest.builder()
                .resourceKey("job:test-retry-disabled")
                .leaseMs(60_000)
                .build());

        // Try acquire with timeout — should NOT retry, just single attempt
        long start = System.currentTimeMillis();
        LockResult result = engine.acquire(LockRequest.builder()
                .resourceKey("job:test-retry-disabled")
                .leaseMs(30_000)
                .waitTimeoutMs(5_000) // 5s timeout — but retry disabled
                .build());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isAcquired());
        assertTrue(elapsed < 1_000, "Should return immediately (single attempt), not wait 5s");
    }
}
