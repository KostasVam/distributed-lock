package com.vamva.distributedlock.integration;

import com.vamva.distributedlock.backend.LockBackend;
import com.vamva.distributedlock.backend.RedisLockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.metrics.LockMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos tests verifying behavior when Redis goes down and recovers.
 * Uses manually managed containers to control start/stop lifecycle.
 */
@Testcontainers
class ChaosTest {

    @Test
    void acquisitionFailsWhenRedisStops() {
        try (GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();

            LockBackend backend = createRedisBackend(redis.getHost(), redis.getFirstMappedPort());

            // Verify working
            assertTrue(backend.acquire("lock:chaos:1", "token-1", 30_000) >= 0);
            backend.release("lock:chaos:1", "token-1");

            // Stop Redis
            redis.stop();

            // Acquisition should fail (fail-closed)
            long fence = backend.acquire("lock:chaos:2", "token-2", 30_000);
            assertEquals(-1L, fence, "Acquisition should fail when Redis is down");
        }
    }

    @Test
    void recoversAfterRedisRestart() throws InterruptedException {
        // Use a fixed exposed port via docker to enable reconnection
        try (GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379)) {
            redis.start();
            String host = redis.getHost();
            int port = redis.getFirstMappedPort();

            LockBackend backend = createRedisBackend(host, port);

            // Verify working
            assertTrue(backend.acquire("lock:chaos:3", "token-1", 500) >= 0);

            // Pause Redis container (keeps port mapping alive, unlike stop)
            redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();

            // Trigger circuit breaker by sending requests that will timeout/fail
            for (int i = 0; i < 10; i++) {
                backend.acquire("lock:chaos:probe:" + i, "token-probe", 1000);
            }

            // Unpause Redis
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();

            // Wait for circuit breaker to transition OPEN → HALF_OPEN (10s default)
            Thread.sleep(12_000);

            // Should recover
            long fence = backend.acquire("lock:chaos:4", "token-2", 30_000);
            assertTrue(fence >= 0, "Should recover after Redis becomes available again");
            backend.release("lock:chaos:4", "token-2");
        }
    }

    private LockBackend createRedisBackend(String host, int port) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        LockMetrics metrics = new LockMetrics(new SimpleMeterRegistry(), "redis");

        return new RedisLockBackend(template, false, metrics,
                new DistributedLockProperties.CircuitBreakerConfig());
    }
}
