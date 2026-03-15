package com.vamva.distributedlock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the distributed lock library, bound from
 * {@code distributed-lock.*} in YAML.
 *
 * <p>Defines the backend type (redis/in-memory), failure mode, default lease,
 * global owner identity, and retry policy.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "distributed-lock")
public class DistributedLockProperties {

    /** Whether the distributed lock library is enabled globally. */
    private boolean enabled = true;

    /**
     * If {@code true}, lock acquisition returns false (instead of throwing)
     * when the backend (Redis) is unavailable. Default is {@code false}
     * (fail-closed: acquisition fails when backend is down).
     */
    private boolean failOpen = false;

    /** Storage backend type: "redis" or "in-memory". */
    private String backend = "redis";

    /** Default lease duration in milliseconds. Used as fallback when LockRequest does not specify leaseMs. */
    private long defaultLeaseMs = 30_000;

    /** Global owner identifier for logging and tracing. Typically the application instance name. */
    private String ownerId;

    /** Retry policy configuration for acquire-with-timeout. */
    private RetryConfig retry = new RetryConfig();

    @Data
    public static class RetryConfig {

        /** Whether retry is enabled for acquire-with-timeout. */
        private boolean enabled = true;

        /** Initial backoff delay in milliseconds before first retry. */
        private long initialBackoffMs = 50;

        /** Maximum backoff delay in milliseconds. */
        private long maxBackoffMs = 2000;

        /** Whether to add random jitter to backoff delays. */
        private boolean jitter = true;
    }
}
