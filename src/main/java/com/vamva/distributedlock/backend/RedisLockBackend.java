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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis-backed distributed lock using Lua scripts for atomic operations.
 *
 * <p>Wraps all Redis calls in a Resilience4j {@link CircuitBreaker} to prevent
 * cascading failures when Redis is unavailable.</p>
 */
@Slf4j
public class RedisLockBackend implements LockBackend {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> renewScript;
    private final boolean failOpen;
    private final LockMetrics metrics;
    private final CircuitBreaker circuitBreaker;

    public RedisLockBackend(StringRedisTemplate redisTemplate, boolean failOpen,
                            LockMetrics metrics,
                            com.vamva.distributedlock.config.DistributedLockProperties.CircuitBreakerConfig cbConfig) {
        this.redisTemplate = redisTemplate;
        this.failOpen = failOpen;
        this.metrics = metrics;

        this.acquireScript = new DefaultRedisScript<>();
        this.acquireScript.setLocation(new ClassPathResource("scripts/acquire.lua"));
        this.acquireScript.setResultType(List.class);

        this.releaseScript = loadScript("scripts/release.lua");
        this.renewScript = loadScript("scripts/renew.lua");

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .minimumNumberOfCalls(cbConfig.getMinimumNumberOfCalls())
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(cbConfig.getWaitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedNumberOfCallsInHalfOpenState())
                .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("redis-distributed-lock");

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker state transition: {}", event));
    }

    @Override
    public long acquire(String key, String token, long leaseMs) {
        return acquireWithFailover(() -> {
            String fenceKey = key + ":fence";
            List result = redisTemplate.execute(acquireScript,
                    Arrays.asList(key, fenceKey), token, String.valueOf(leaseMs));
            if (result != null && result.size() >= 2 && ((Number) result.get(0)).longValue() == 1L) {
                return ((Number) result.get(1)).longValue();
            }
            return -1L;
        });
    }

    @Override
    public boolean release(String key, String token) {
        return executeWithCircuitBreaker(() -> {
            Long result = redisTemplate.execute(releaseScript,
                    Collections.singletonList(key), token);
            if (result != null && result == -1L) {
                log.debug("Release rejected: token mismatch for key={}", key);
            }
            return result != null && result == 1L;
        }, "release");
    }

    @Override
    public boolean renew(String key, String token, long leaseMs) {
        return executeWithCircuitBreaker(() -> {
            Long result = redisTemplate.execute(renewScript,
                    Collections.singletonList(key), token, String.valueOf(leaseMs));
            if (result != null && result == -1L) {
                log.debug("Renew rejected: token mismatch for key={}", key);
            }
            return result != null && result == 1L;
        }, "renew");
    }

    private long acquireWithFailover(Supplier<Long> operation) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (Exception e) {
            log.error("Redis acquire failed (circuit={}): {}", circuitBreaker.getState(), e.getMessage());
            metrics.recordBackendError();
            if (failOpen) {
                log.warn("Fail-open: allowing lock acquisition despite backend error");
                return 0L; // synthetic success with fence=0 (caller should treat as unverified)
            }
            return -1L;
        }
    }

    private boolean executeWithCircuitBreaker(Supplier<Boolean> operation, String operationName) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (Exception e) {
            log.error("Redis {} failed (circuit={}): {}", operationName, circuitBreaker.getState(), e.getMessage());
            metrics.recordBackendError();
            return false;
        }
    }

    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
