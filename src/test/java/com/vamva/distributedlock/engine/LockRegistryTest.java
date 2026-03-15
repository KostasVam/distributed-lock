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

import static org.junit.jupiter.api.Assertions.*;

class LockRegistryTest {

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
    void shutdownReleasesAllActiveLocks() {
        // Acquire 3 locks and register them
        for (int i = 1; i <= 3; i++) {
            LockResult result = engine.tryAcquire(LockRequest.builder()
                    .resourceKey("registry:test:" + i).leaseMs(30_000).build());
            LockHandle handle = new LockHandle(result, engine, registry);
            registry.register(handle);
        }

        // Shutdown should release all
        registry.shutdown();

        // All locks should be free
        for (int i = 1; i <= 3; i++) {
            LockResult reacquire = engine.tryAcquire(LockRequest.builder()
                    .resourceKey("registry:test:" + i).leaseMs(30_000).build());
            assertTrue(reacquire.isAcquired(), "Lock " + i + " should be free after shutdown");
        }
    }

    @Test
    void unregisterRemovesFromActiveSet() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("registry:test:unreg").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry);
        registry.register(handle);

        // Close unregisters
        handle.close();

        // Shutdown should have nothing to release
        assertDoesNotThrow(registry::shutdown);
    }

    @Test
    void shutdownIsIdempotent() {
        LockResult result = engine.tryAcquire(LockRequest.builder()
                .resourceKey("registry:test:idempotent").leaseMs(30_000).build());
        LockHandle handle = new LockHandle(result, engine, registry);
        registry.register(handle);

        registry.shutdown();
        assertDoesNotThrow(registry::shutdown, "Double shutdown should be safe");
    }
}
