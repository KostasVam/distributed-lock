package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import com.vamva.distributedlock.token.UuidTokenGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LockHandleTest {

    private LockEngine engine;
    private LockRegistry registry;

    @BeforeEach
    void setUp() {
        InMemoryLockBackend backend = new InMemoryLockBackend();
        DistributedLockProperties properties = new DistributedLockProperties();
        properties.setBackend("in-memory");
        properties.setDefaultLeaseMs(30_000);
        LockMetrics metrics = new LockMetrics(new SimpleMeterRegistry(), "in-memory");
        engine = new LockEngine(backend, new UuidTokenGenerator(), metrics,
                Clock.systemUTC(), properties, ObservationRegistry.NOOP);
        registry = new LockRegistry();
    }

    @Test
    void closeReleasesLock() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:1").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry);
        registry.register(handle);

        assertTrue(handle.isAcquired());
        handle.close();
        assertFalse(handle.isAcquired());

        // Lock should be free now
        LockResult reacquire = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:1").leaseMs(30_000).build());
        assertTrue(reacquire.isAcquired());
    }

    @Test
    void doubleCloseIsSafe() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:2").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry);
        registry.register(handle);

        handle.close();
        assertDoesNotThrow(handle::close, "Double close should be safe");
    }

    @Test
    void tryWithResourcesReleasesLock() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("handle:test:3").leaseMs(30_000).build());

        try (LockHandle handle = new LockHandle(result, engine, registry)) {
            registry.register(handle);
            assertTrue(handle.isAcquired());
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
        LockHandle handle = new LockHandle(result, engine, registry);
        registry.register(handle);
        handle.startAutoRenewal(200, 500, scheduler);

        // Wait longer than original lease — auto-renewal should keep it alive
        Thread.sleep(800);

        assertTrue(handle.isAcquired(), "Lock should still be acquired via auto-renewal");

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
        LockHandle handle = new LockHandle(result, engine, registry);
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
        LockHandle handle = new LockHandle(result, engine, registry);

        assertEquals("handle:test:6", handle.getResourceKey());
        assertNotNull(handle.getLockToken());
        assertTrue(handle.getFencingToken() > 0);
        assertEquals(result, handle.getLockResult());
    }
}
