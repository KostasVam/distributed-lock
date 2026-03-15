package com.vamva.distributedlock.integration;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import com.vamva.distributedlock.api.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
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
class AnnotationIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private AnnotatedTestService testService;

    @Autowired
    private DistributedLockClient lockClient;

    @BeforeEach
    void reset() {
        testService.resetCount();
    }

    @Test
    void staticKeyMethodExecutesWithLock() {
        String result = testService.staticKeyMethod();
        assertEquals("executed", result);
        assertEquals(1, testService.getExecutionCount());
    }

    @Test
    void spelKeyResolvesParameter() {
        String result = testService.spelKeyMethod(42);
        assertEquals("executed-42", result);
    }

    @Test
    void annotatedMethodBlockedWhenLockHeld() {
        // Pre-acquire the lock manually
        LockResult held = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("annotation:test:static-key")
                .leaseMs(30_000)
                .build());
        assertTrue(held.isAcquired());

        try {
            // Annotated method should fail to acquire
            assertThrows(LockAcquisitionException.class, () -> testService.staticKeyMethod());
            assertEquals(0, testService.getExecutionCount(), "Method should not have executed");
        } finally {
            lockClient.release(held.getResourceKey(), held.getLockToken());
        }
    }

    @Test
    void lockReleasedAfterMethodCompletes() {
        testService.staticKeyMethod();

        // Lock should be released — can acquire again
        LockResult result = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("annotation:test:static-key")
                .leaseMs(30_000)
                .build());
        assertTrue(result.isAcquired(), "Lock should be free after annotated method completes");
        lockClient.release(result.getResourceKey(), result.getLockToken());
    }

    @Test
    void blankSpelKeyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> testService.nullableKeyMethod(""));
        assertEquals(0, testService.getExecutionCount(), "Method should not have executed");
    }

    @Test
    void nullSpelKeyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> testService.nullableKeyMethod(null));
        assertEquals(0, testService.getExecutionCount(), "Method should not have executed");
    }

    @Test
    void differentSpelKeysAreIndependent() {
        // These use different IDs so different lock keys
        String r1 = testService.spelKeyMethod(1);
        String r2 = testService.spelKeyMethod(2);

        assertEquals("executed-1", r1);
        assertEquals("executed-2", r2);
        assertEquals(2, testService.getExecutionCount());
    }

    @Test
    void autoRenewKeepsLockDuringLongMethod() throws InterruptedException {
        // Method runs 2.5s with 1s lease — autoRenew=true keeps it alive
        String result = testService.autoRenewMethod();
        assertEquals("completed", result);
        assertEquals(1, testService.getExecutionCount());

        // Lock should be released after method returns
        LockResult free = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("annotation:test:autorenew")
                .leaseMs(30_000)
                .build());
        assertTrue(free.isAcquired(), "Lock should be free after autoRenew method completes");
        lockClient.release(free.getResourceKey(), free.getLockToken());
    }
}
