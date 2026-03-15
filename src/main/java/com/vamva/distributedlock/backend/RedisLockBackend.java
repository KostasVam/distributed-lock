package com.vamva.distributedlock.backend;

import com.vamva.distributedlock.metrics.LockMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Redis-backed distributed lock using Lua scripts for atomic operations.
 *
 * <p>Wraps all Redis calls in a Resilience4j {@link CircuitBreaker} to prevent
 * cascading failures when Redis is unavailable. The circuit breaker transitions:</p>
 * <ul>
 *   <li><strong>CLOSED</strong> → normal operation, calls go through</li>
 *   <li><strong>OPEN</strong> → after failure threshold, calls short-circuit (fail-closed)</li>
 *   <li><strong>HALF_OPEN</strong> → after wait duration, allows probe calls to test recovery</li>
 * </ul>
 */
@Slf4j
public class RedisLockBackend implements LockBackend {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> renewScript;
    private final boolean failOpen;
    private final LockMetrics metrics;
    private final CircuitBreaker circuitBreaker;

    public RedisLockBackend(StringRedisTemplate redisTemplate, boolean failOpen, LockMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.failOpen = failOpen;
        this.metrics = metrics;

        this.acquireScript = loadScript("scripts/acquire.lua");
        this.releaseScript = loadScript("scripts/release.lua");
        this.renewScript = loadScript("scripts/renew.lua");

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("redis-distributed-lock");

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker state transition: {}", event));
    }

    @Override
    public boolean acquire(String key, String token, long leaseMs) {
        return executeWithCircuitBreaker(() -> {
            Long result = redisTemplate.execute(acquireScript,
                    Collections.singletonList(key), token, String.valueOf(leaseMs));
            return result != null && result == 1L;
        }, "acquire");
    }

    @Override
    public boolean release(String key, String token) {
        return executeWithCircuitBreaker(() -> {
            Long result = redisTemplate.execute(releaseScript,
                    Collections.singletonList(key), token);
            return result != null && result == 1L;
        }, "release");
    }

    @Override
    public boolean renew(String key, String token, long leaseMs) {
        return executeWithCircuitBreaker(() -> {
            Long result = redisTemplate.execute(renewScript,
                    Collections.singletonList(key), token, String.valueOf(leaseMs));
            return result != null && result == 1L;
        }, "renew");
    }

    /**
     * Executes a Redis operation through the circuit breaker.
     * On failure (or when circuit is open), falls back to fail-open/closed behavior.
     */
    private boolean executeWithCircuitBreaker(Supplier<Boolean> operation, String operationName) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (Exception e) {
            log.error("Redis {} failed (circuit={}): {}", operationName, circuitBreaker.getState(), e.getMessage());
            metrics.recordBackendError();
            return handleFailure(operationName);
        }
    }

    private boolean handleFailure(String operationName) {
        if (failOpen && "acquire".equals(operationName)) {
            log.warn("Fail-open: returning false for lock acquisition due to backend error");
        }
        // Locks always fail-closed by default: acquisition fails, release/renew fail
        return false;
    }

    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
