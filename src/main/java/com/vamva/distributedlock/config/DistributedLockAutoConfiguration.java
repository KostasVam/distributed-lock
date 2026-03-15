package com.vamva.distributedlock.config;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.backend.InMemoryLockBackend;
import com.vamva.distributedlock.backend.LockBackend;
import com.vamva.distributedlock.backend.RedisLockBackend;
import com.vamva.distributedlock.engine.LockEngine;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.token.TokenGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Spring Boot auto-configuration for the distributed lock library.
 *
 * <p>All beans are declared here rather than via component scanning,
 * making this a proper Spring Boot starter. Consumer applications only need to
 * add the dependency and configure in YAML — no package scanning required.</p>
 *
 * <p>Beans are conditional on {@code distributed-lock.enabled=true} (default).
 * The backend selection is controlled by {@code distributed-lock.backend} (redis/in-memory).</p>
 *
 * <p>All beans use {@link ConditionalOnMissingBean} where appropriate, allowing
 * consumer applications to override any component.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(DistributedLockProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "distributed-lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockAutoConfiguration {

    // ── Time ─────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock distributedLockClock() {
        return Clock.systemUTC();
    }

    // ── Backend ──────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "distributed-lock.backend", havingValue = "redis", matchIfMissing = true)
    @ConditionalOnMissingBean(LockBackend.class)
    public LockBackend redisLockBackend(StringRedisTemplate redisTemplate,
                                        DistributedLockProperties properties,
                                        LockMetrics metrics) {
        return new RedisLockBackend(redisTemplate, properties.isFailOpen(), metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "distributed-lock.backend", havingValue = "in-memory")
    @ConditionalOnMissingBean(LockBackend.class)
    public LockBackend inMemoryLockBackend(Clock clock) {
        return new InMemoryLockBackend(clock);
    }

    // ── Token ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(TokenGenerator.class)
    public TokenGenerator tokenGenerator() {
        return new TokenGenerator();
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(LockMetrics.class)
    public LockMetrics lockMetrics(MeterRegistry meterRegistry, DistributedLockProperties properties) {
        return new LockMetrics(meterRegistry, properties.getBackend());
    }

    // ── Observation ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ObservationRegistry.class)
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.NOOP;
    }

    // ── Engine ───────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(LockEngine.class)
    public LockEngine lockEngine(LockBackend backend, TokenGenerator tokenGenerator,
                                 LockMetrics metrics, Clock clock,
                                 DistributedLockProperties properties,
                                 ObservationRegistry observationRegistry) {
        return new LockEngine(backend, tokenGenerator, metrics, clock, properties, observationRegistry);
    }

    // ── Client API ───────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(DistributedLockClient.class)
    public DistributedLockClient distributedLockClient(LockEngine engine) {
        return new DistributedLockClient(engine);
    }
}
