package com.vamva.distributedlock.integration;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FencingTokenIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private DistributedLockClient lockClient;

    @Test
    void acquireReturnsFencingToken() {
        LockResult result = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:1")
                .leaseMs(30_000)
                .build());

        assertTrue(result.isAcquired());
        assertTrue(result.getFencingToken() > 0, "Fencing token should be positive");

        lockClient.release(result.getResourceKey(), result.getLockToken());
    }

    @Test
    void fencingTokenIncrementsAcrossReacquisitions() {
        LockResult first = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:2")
                .leaseMs(30_000)
                .build());
        long fence1 = first.getFencingToken();
        lockClient.release(first.getResourceKey(), first.getLockToken());

        LockResult second = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:2")
                .leaseMs(30_000)
                .build());
        long fence2 = second.getFencingToken();
        lockClient.release(second.getResourceKey(), second.getLockToken());

        LockResult third = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:2")
                .leaseMs(30_000)
                .build());
        long fence3 = third.getFencingToken();
        lockClient.release(third.getResourceKey(), third.getLockToken());

        assertTrue(fence2 > fence1, "Second fence should be greater than first");
        assertTrue(fence3 > fence2, "Third fence should be greater than second");
    }

    @Test
    void differentResourceKeysHaveIndependentFenceCounters() {
        LockResult a = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:3a")
                .leaseMs(30_000)
                .build());
        LockResult b = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:3b")
                .leaseMs(30_000)
                .build());

        // Both should have fence token 1 (first acquire for each key)
        assertEquals(a.getFencingToken(), b.getFencingToken(),
                "Independent keys should have independent fence counters starting at 1");

        lockClient.release(a.getResourceKey(), a.getLockToken());
        lockClient.release(b.getResourceKey(), b.getLockToken());
    }

    @Test
    void failedAcquireHasZeroFencingToken() {
        LockResult held = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:4")
                .leaseMs(30_000)
                .build());

        LockResult failed = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("fence:test:4")
                .leaseMs(30_000)
                .build());

        assertFalse(failed.isAcquired());
        assertEquals(0, failed.getFencingToken(), "Failed acquire should have fence token 0");

        lockClient.release(held.getResourceKey(), held.getLockToken());
    }
}
