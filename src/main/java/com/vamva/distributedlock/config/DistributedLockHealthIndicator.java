package com.vamva.distributedlock.config;

import com.vamva.distributedlock.backend.LockBackend;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot health indicator for the distributed lock backend.
 *
 * <p>Performs a probe acquire/release cycle to verify the backend is operational.
 * Reports UP if the backend responds correctly, DOWN if it throws.</p>
 */
public class DistributedLockHealthIndicator implements HealthIndicator {

    private static final String HEALTH_CHECK_KEY = "lock:__health_check__";
    private static final String HEALTH_CHECK_TOKEN = "__health_probe__";
    private static final long HEALTH_CHECK_LEASE_MS = 1000;

    private final LockBackend backend;
    private final String backendType;

    public DistributedLockHealthIndicator(LockBackend backend, String backendType) {
        this.backend = backend;
        this.backendType = backendType;
    }

    @Override
    public Health health() {
        try {
            boolean acquired = backend.acquire(HEALTH_CHECK_KEY, HEALTH_CHECK_TOKEN, HEALTH_CHECK_LEASE_MS);
            if (acquired) {
                backend.release(HEALTH_CHECK_KEY, HEALTH_CHECK_TOKEN);
            }
            return Health.up()
                    .withDetail("backend", backendType)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("backend", backendType)
                    .withException(e)
                    .build();
        }
    }
}
