package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockResult;
import com.vamva.distributedlock.token.UuidTokenGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LockHandleTest {

    private LockEngine engine;
    private LockRegistry registry;
    private LockMetrics metrics;

    @BeforeEach
    void setUp() {
        InMemoryLockBackend backend = new InMemoryLockBackend();
        DistributedLockProperties properties = new DistributedLockProperties();
        properties.setBackend("in-memory");
        properties.setDefaultLeaseMs(30_000);
        metrics = new LockMetrics(new SimpleMeterRegistry(), "in-memory");
        engine = new LockEngine(backend, new UuidTokenGenerator(), metrics,
                Clock.systemUTC(), properties, ObservationRegistry.NOOP);
        registry = new LockRegistry();
    }

    @Test
    void closeReleasesLock() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:1").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);

        assertTrue(handle.isHeld());
        handle.close();
        assertFalse(handle.isHeld());

        // Lock should be free now
        LockResult reacquire = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:1").leaseMs(30_000).build());
        assertTrue(reacquire.isAcquired());
    }

    @Test
    void doubleCloseIsSafe() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:2").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);

        handle.close();
        assertDoesNotThrow(handle::close, "Double close should be safe");
    }

    @Test
    void tryWithResourcesReleasesLock() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:3").leaseMs(30_000).build());

        try (LockHandle handle = new LockHandle(result, engine, registry, metrics)) {
            registry.register(handle);
            assertTrue(handle.isHeld());
        }

        // Lock should be free
        LockResult reacquire = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:3").leaseMs(30_000).build());
        assertTrue(reacquire.isAcquired());
    }

    @Test
    void autoRenewalKeepsLockAlive() throws InterruptedException {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:4").leaseMs(500).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);
        handle.startAutoRenewal(200, 500, scheduler);

        // Wait longer than original lease — auto-renewal should keep it alive
        Thread.sleep(800);

        assertTrue(handle.isHeld(), "Lock should still be acquired via auto-renewal");

        // Another client should not be able to acquire
        LockResult blocked = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:4").leaseMs(30_000).build());
        assertFalse(blocked.isAcquired(), "Lock should still be held");

        handle.close();
        scheduler.shutdownNow();
    }

    @Test
    void closeStopsAutoRenewal() throws InterruptedException {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:5").leaseMs(300).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);
        handle.startAutoRenewal(100, 300, scheduler);

        handle.close();
        Thread.sleep(500);

        // Lock should be free after close + lease expiry
        LockResult reacquire = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:5").leaseMs(30_000).build());
        assertTrue(reacquire.isAcquired());
        scheduler.shutdownNow();
    }

    @Test
    void exposesLockResultFields() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:6").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry, metrics);

        assertEquals("handle:test:6", handle.getResourceKey());
        assertNotNull(handle.getLockToken());
        assertTrue(handle.getFencingToken() > 0);
        assertEquals(result, handle.getLockResult());
        assertEquals(LockHandle.State.HELD, handle.getState());
    }

    @Test
    void stateTransitionsToLostWhenRenewalFails() throws InterruptedException {
        // Acquire with very short lease, don't renew from engine side
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:lost:1").leaseMs(100).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);
        // Start renewal at 50ms interval, but lease is only 100ms.
        // After lease expires, another client could acquire, causing renewal to fail.
        handle.startAutoRenewal(50, 100, scheduler);

        // Release the lock externally to simulate another client taking it
        engine.release(result.getResourceKey(), result.getLockToken());

        // Wait for renewal to detect loss
        Thread.sleep(300);

        assertEquals(LockHandle.State.LOST, handle.getState(), "State should be LOST after renewal failure");
        assertFalse(handle.isHeld());

        handle.close();
        assertEquals(LockHandle.State.RELEASED, handle.getState());
        scheduler.shutdownNow();
    }

    @Test
    void onLockLostCallbackInvokedOnLoss() throws InterruptedException {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:lost:2").leaseMs(100).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);

        AtomicReference<String> lostKey = new AtomicReference<>();
        handle.onLockLost(lostKey::set);
        handle.startAutoRenewal(50, 100, scheduler);

        // Release externally
        engine.release(result.getResourceKey(), result.getLockToken());

        Thread.sleep(300);

        assertEquals("handle:test:lost:2", lostKey.get(), "Callback should receive resource key");
        assertEquals(LockHandle.State.LOST, handle.getState());

        handle.close();
        scheduler.shutdownNow();
    }

    @Test
    void closeFromLostStateSkipsRelease() throws InterruptedException {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:lost:3").leaseMs(100).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);
        handle.startAutoRenewal(50, 100, scheduler);

        // Release externally
        engine.release(result.getResourceKey(), result.getLockToken());
        Thread.sleep(300);

        assertEquals(LockHandle.State.LOST, handle.getState());

        // Close should not throw and should transition to RELEASED
        assertDoesNotThrow(handle::close);
        assertEquals(LockHandle.State.RELEASED, handle.getState());
        scheduler.shutdownNow();
    }

    @Test
    void callbackExceptionDoesNotBreakStateTransition() throws InterruptedException {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:lost:4").leaseMs(100).build());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);

        handle.onLockLost(key -> { throw new RuntimeException("callback exploded"); });
        handle.startAutoRenewal(50, 100, scheduler);

        engine.release(result.getResourceKey(), result.getLockToken());
        Thread.sleep(300);

        // State should still transition despite callback exception
        assertEquals(LockHandle.State.LOST, handle.getState());

        handle.close();
        scheduler.shutdownNow();
    }

    @Test
    void handleWithoutAutoRenewalCloseReleasesNormally() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:no-renew").leaseMs(30_000).build());

        LockHandle handle = new LockHandle(result, engine, registry, metrics);
        registry.register(handle);

        // No startAutoRenewal called
        assertTrue(handle.isHeld());
        handle.close();
        assertEquals(LockHandle.State.RELEASED, handle.getState());
    }
}
