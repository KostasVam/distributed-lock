package com.vamva.distributedlock.api;

import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.engine.LockEngine;
import com.vamva.distributedlock.engine.LockHandle;
import com.vamva.distributedlock.engine.LockRegistry;
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

class DistributedLockClientTest {

    private DistributedLockClient client;

    @BeforeEach
    void setUp() {
        InMemoryLockBackend backend = new InMemoryLockBackend();
        DistributedLockProperties properties = new DistributedLockProperties();
        properties.setBackend("in-memory");
        properties.setDefaultLeaseMs(30_000);
        LockMetrics metrics = new LockMetrics(new SimpleMeterRegistry(), "in-memory");
        LockEngine engine = new LockEngine(backend, new UuidTokenGenerator(), metrics,
                Clock.systemUTC(), properties, ObservationRegistry.NOOP);
        LockRegistry registry = new LockRegistry();
        client = new DistributedLockClient(engine, registry, metrics);
    }

    @Test
    void executeWithLockCallableReturnsResult() throws Exception {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:1").leaseMs(30_000).build();

        String result = client.executeWithLock(request, () -> "hello");

        assertEquals("hello", result);
    }

    @Test
    void executeWithLockRunnableWorks() throws Exception {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:2").leaseMs(30_000).build();

        boolean[] ran = {false};
        client.executeWithLock(request, () -> ran[0] = true);

        assertTrue(ran[0]);
    }

    @Test
    void executeWithLockReleasesOnException() {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:3").leaseMs(30_000).build();

        assertThrows(RuntimeException.class, () ->
                client.executeWithLock(request, () -> {
                    throw new RuntimeException("boom");
                }));

        // Lock should be free
        LockResult retry = client.tryAcquire(request);
        assertTrue(retry.isAcquired());
    }

    @Test
    void executeWithLockThrowsWhenLockHeld() {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:4").leaseMs(30_000).build();

        client.tryAcquire(request);

        assertThrows(LockAcquisitionException.class, () ->
                client.executeWithLock(request, () -> "should not run"));
    }

    @Test
    void acquireWithAutoRenewReturnsHandle() throws InterruptedException {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:5").leaseMs(30_000).build();

        try (LockHandle handle = client.acquireWithAutoRenew(request)) {
            assertTrue(handle.isHeld());
            assertNotNull(handle.getLockToken());
        }
    }

    @Test
    void acquireWithAutoRenewThrowsWhenLockHeld() {
        LockRequest request = LockRequest.builder()
                .resourceKey("client:test:6").leaseMs(30_000).build();

        client.tryAcquire(request);

        assertThrows(LockAcquisitionException.class, () ->
                client.acquireWithAutoRenew(request));
    }
}
