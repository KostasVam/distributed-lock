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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the distributed lock library using a real Redis instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DistributedLockIntegrationTest {

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
    void acquireAndReleaseLock() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:1")
                .leaseMs(30_000)
                .ownerId("test-owner")
                .build();

        LockResult result = lockClient.tryAcquire(request);

        assertTrue(result.isAcquired());
        assertNotNull(result.getLockToken());

        boolean released = lockClient.release(result.getResourceKey(), result.getLockToken());
        assertTrue(released);
    }

    @Test
    void secondClientBlockedWhileLockHeld() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:2")
                .leaseMs(30_000)
                .build();

        LockResult first = lockClient.tryAcquire(request);
        assertTrue(first.isAcquired());

        LockResult second = lockClient.tryAcquire(request);
        assertFalse(second.isAcquired());

        lockClient.release(first.getResourceKey(), first.getLockToken());
    }

    @Test
    void renewExtendsLease() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:3")
                .leaseMs(5_000)
                .build();

        LockResult result = lockClient.tryAcquire(request);
        assertTrue(result.isAcquired());

        boolean renewed = lockClient.renew(result.getResourceKey(), result.getLockToken(), 60_000);
        assertTrue(renewed);

        lockClient.release(result.getResourceKey(), result.getLockToken());
    }

    @Test
    void nonOwnerCannotRelease() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:4")
                .leaseMs(30_000)
                .build();

        LockResult result = lockClient.tryAcquire(request);
        assertTrue(result.isAcquired());

        boolean released = lockClient.release(result.getResourceKey(), "wrong-token");
        assertFalse(released);

        lockClient.release(result.getResourceKey(), result.getLockToken());
    }

    @Test
    void nonOwnerCannotRenew() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:5")
                .leaseMs(30_000)
                .build();

        LockResult result = lockClient.tryAcquire(request);
        assertTrue(result.isAcquired());

        boolean renewed = lockClient.renew(result.getResourceKey(), "wrong-token", 60_000);
        assertFalse(renewed);

        lockClient.release(result.getResourceKey(), result.getLockToken());
    }

    @Test
    void expiredLockBecomesAvailable() throws InterruptedException {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:6")
                .leaseMs(500) // short lease
                .build();

        LockResult first = lockClient.tryAcquire(request);
        assertTrue(first.isAcquired());

        // Wait for expiration
        Thread.sleep(700);

        LockResult second = lockClient.tryAcquire(request);
        assertTrue(second.isAcquired(), "Expired lock should be available");

        lockClient.release(second.getResourceKey(), second.getLockToken());
    }

    @Test
    void concurrentAcquisitionsProduceExactlyOneWinner() throws InterruptedException {
        int contenders = 10;
        String resourceKey = "integration:test:7";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(contenders);
        AtomicInteger winners = new AtomicInteger(0);
        List<LockResult> results = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(contenders);

        for (int i = 0; i < contenders; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    LockRequest request = LockRequest.builder()
                            .resourceKey(resourceKey)
                            .leaseMs(30_000)
                            .build();
                    LockResult result = lockClient.tryAcquire(request);
                    results.add(result);
                    if (result.isAcquired()) {
                        winners.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Unexpected exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all contenders simultaneously
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, winners.get(), "Exactly one contender should win");

        // Cleanup
        results.stream()
                .filter(LockResult::isAcquired)
                .findFirst()
                .ifPresent(r -> lockClient.release(r.getResourceKey(), r.getLockToken()));
    }

    @Test
    void executeWithLockProtectsExecution() throws Exception {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:8")
                .leaseMs(30_000)
                .build();

        String result = lockClient.executeWithLock(request, () -> {
            // Verify lock is held — another acquire should fail
            LockResult second = lockClient.tryAcquire(LockRequest.builder()
                    .resourceKey("integration:test:8")
                    .leaseMs(30_000)
                    .build());
            assertFalse(second.isAcquired(), "Lock should be held during execution");
            return "completed";
        });

        assertEquals("completed", result);

        // Lock should be released after executeWithLock
        LockResult afterRelease = lockClient.tryAcquire(request);
        assertTrue(afterRelease.isAcquired(), "Lock should be released after execution");
        lockClient.release(afterRelease.getResourceKey(), afterRelease.getLockToken());
    }

    @Test
    void executeWithLockReleasesOnException() {
        LockRequest request = LockRequest.builder()
                .resourceKey("integration:test:exception")
                .leaseMs(30_000)
                .build();

        assertThrows(RuntimeException.class, () ->
                lockClient.executeWithLock(request, () -> {
                    throw new RuntimeException("Task failed");
                }));

        // Lock should be released despite exception
        LockResult retry = lockClient.tryAcquire(request);
        assertTrue(retry.isAcquired(), "Lock should be released after task exception");
        lockClient.release(retry.getResourceKey(), retry.getLockToken());
    }

    @Test
    void acquireWithTimeoutSucceeds() throws InterruptedException {
        LockRequest holdRequest = LockRequest.builder()
                .resourceKey("integration:test:9")
                .leaseMs(500) // short lease
                .build();

        LockResult held = lockClient.tryAcquire(holdRequest);
        assertTrue(held.isAcquired());

        LockRequest waitRequest = LockRequest.builder()
                .resourceKey("integration:test:9")
                .leaseMs(30_000)
                .waitTimeoutMs(3000)
                .build();

        LockResult result = lockClient.acquire(waitRequest);
        assertTrue(result.isAcquired(), "Should acquire after original lock expires");

        lockClient.release(result.getResourceKey(), result.getLockToken());
    }
}
