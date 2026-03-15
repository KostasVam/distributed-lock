package com.vamva.distributedlock.integration;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.api.LockAcquisitionException;
import com.vamva.distributedlock.engine.LockHandle;
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
class AutoRenewIntegrationTest {

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
    void autoRenewKeepsLockAliveAfterOriginalLease() throws InterruptedException {
        // Acquire with 1s lease + auto-renew
        try (LockHandle handle = lockClient.acquireWithAutoRenew(LockRequest.builder()
                .resourceKey("autorenew:test:1")
                .leaseMs(1_000) // 1 second lease
                .build())) {

            assertTrue(handle.isHeld());

            // Wait 2x the lease — without auto-renew, lock would have expired
            Thread.sleep(2_500);

            // Another client should NOT be able to acquire (auto-renew kept it alive)
            LockResult blocked = lockClient.tryAcquire(LockRequest.builder()
                    .resourceKey("autorenew:test:1")
                    .leaseMs(30_000)
                    .build());
            assertFalse(blocked.isAcquired(), "Lock should still be held via auto-renew");
        }

        // After close, lock should be free
        LockResult free = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("autorenew:test:1")
                .leaseMs(30_000)
                .build());
        assertTrue(free.isAcquired(), "Lock should be free after LockHandle.close()");
        lockClient.release(free.getResourceKey(), free.getLockToken());
    }

    @Test
    void acquireWithAutoRenewThrowsWhenLockHeld() {
        LockResult held = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("autorenew:test:2")
                .leaseMs(30_000)
                .build());
        assertTrue(held.isAcquired());

        try {
            assertThrows(LockAcquisitionException.class, () ->
                    lockClient.acquireWithAutoRenew(LockRequest.builder()
                            .resourceKey("autorenew:test:2")
                            .leaseMs(30_000)
                            .build()));
        } finally {
            lockClient.release(held.getResourceKey(), held.getLockToken());
        }
    }

    @Test
    void lockHandleExposesCorrectFields() throws InterruptedException {
        try (LockHandle handle = lockClient.acquireWithAutoRenew(LockRequest.builder()
                .resourceKey("autorenew:test:3")
                .leaseMs(30_000)
                .build())) {

            assertEquals("autorenew:test:3", handle.getResourceKey());
            assertNotNull(handle.getLockToken());
            assertTrue(handle.getFencingToken() > 0);
            assertTrue(handle.isHeld());
        }
    }
}
