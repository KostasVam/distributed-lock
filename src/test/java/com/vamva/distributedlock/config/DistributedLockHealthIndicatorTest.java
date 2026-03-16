package com.vamva.distributedlock.config;

import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.backend.LockBackend;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockHealthIndicatorTest {

    @Test
    void healthyWhenBackendWorks() {
        LockBackend backend = new InMemoryLockBackend();
        DistributedLockHealthIndicator indicator = new DistributedLockHealthIndicator(backend, "in-memory");

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("in-memory", health.getDetails().get("backend"));
    }

    @Test
    void unhealthyWhenBackendThrows() {
        LockBackend failingBackend = new LockBackend() {
            @Override
            public long acquire(String key, String token, long leaseMs) {
                throw new RuntimeException("connection refused");
            }
            @Override
            public boolean release(String key, String token) {
                throw new RuntimeException("connection refused");
            }
            @Override
            public boolean renew(String key, String token, long leaseMs) {
                throw new RuntimeException("connection refused");
            }
        };

        DistributedLockHealthIndicator indicator = new DistributedLockHealthIndicator(failingBackend, "redis");

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("redis", health.getDetails().get("backend"));
    }

    @Test
    void healthCheckReleasesProbeKey() {
        InMemoryLockBackend backend = new InMemoryLockBackend();
        DistributedLockHealthIndicator indicator = new DistributedLockHealthIndicator(backend, "in-memory");

        indicator.health();
        indicator.health();
        indicator.health();

        // Probe key should not accumulate locks
        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
    }
}
