package com.vamva.distributedlock.engine;

import com.vamva.distributedlock.backend.LockBackend;
import com.vamva.distributedlock.config.DistributedLockProperties;
import com.vamva.distributedlock.metrics.LockMetrics;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import com.vamva.distributedlock.token.TokenGenerator;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Core lock engine that orchestrates acquisition, renewal, and release.
 *
 * <p>Handles retry logic with configurable exponential backoff and jitter for
 * acquire-with-timeout semantics. Single-attempt try-lock is also supported
 * when {@link LockRequest#getWaitTimeoutMs()} is zero.</p>
 *
 * <p>Uses an injected {@link Clock} for all time operations, enabling
 * deterministic testing.</p>
 */
@Slf4j
public class LockEngine {

    private final LockBackend backend;
    private final TokenGenerator tokenGenerator;
    private final LockMetrics metrics;
    private final Clock clock;
    private final DistributedLockProperties properties;
    private final ObservationRegistry observationRegistry;

    public LockEngine(LockBackend backend, TokenGenerator tokenGenerator,
                      LockMetrics metrics, Clock clock,
                      DistributedLockProperties properties,
                      ObservationRegistry observationRegistry) {
        this.backend = backend;
        this.tokenGenerator = tokenGenerator;
        this.metrics = metrics;
        this.clock = clock;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Attempts to acquire a lock once (try-lock semantics).
     *
     * @param request the lock request
     * @return the lock result
     */
    public LockResult tryAcquire(LockRequest request) {
        validateRequest(request);
        String resourceKey = request.getResourceKey();
        long leaseMs = resolveLeaseMs(request);
        String ownerId = resolveOwnerId(request);
        String token = tokenGenerator.generate();
        String key = formatKey(resourceKey);
        String keyHash = hashResourceKey(resourceKey);

        Observation observation = Observation.createNotStarted("lock.acquire", observationRegistry)
                .lowCardinalityKeyValue("backend", properties.getBackend())
                .lowCardinalityKeyValue("result", "pending")
                .highCardinalityKeyValue("resource_group", extractResourceGroup(resourceKey))
                .start();

        metrics.recordAcquireAttempt();
        long start = System.nanoTime();

        log.info("operation=acquire_attempt resource_key_hash={} owner_id={} lease_ms={} backend={}",
                keyHash, ownerId, leaseMs, properties.getBackend());

        try {
            boolean acquired = backend.acquire(key, token, leaseMs);
            long durationNanos = System.nanoTime() - start;
            metrics.recordAcquireDuration(durationNanos);

            if (acquired) {
                long expiresAt = clock.millis() + leaseMs;
                metrics.recordAcquireSuccess();
                observation.lowCardinalityKeyValue("result", "success");
                log.info("operation=acquire_success resource_key_hash={} owner_id={} token={} lease_ms={} backend={}",
                        keyHash, ownerId, token, leaseMs, properties.getBackend());
                return LockResult.success(resourceKey, token, leaseMs, expiresAt);
            }

            metrics.recordAcquireFailed();
            observation.lowCardinalityKeyValue("result", "failed");
            log.info("operation=acquire_failed resource_key_hash={} owner_id={} reason=already_held backend={}",
                    keyHash, ownerId, properties.getBackend());
            return LockResult.failure(resourceKey);
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Acquires a lock, retrying with exponential backoff until timeout.
     *
     * <p>If {@code waitTimeoutMs} is zero or negative,
     * delegates to {@link #tryAcquire(LockRequest)}.</p>
     *
     * @param request the lock request
     * @return the lock result
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public LockResult acquire(LockRequest request) throws InterruptedException {
        validateRequest(request);
        if (request.getWaitTimeoutMs() <= 0) {
            return tryAcquire(request);
        }

        String resourceKey = request.getResourceKey();
        long leaseMs = resolveLeaseMs(request);
        String ownerId = resolveOwnerId(request);
        String token = tokenGenerator.generate();
        String key = formatKey(resourceKey);
        String keyHash = hashResourceKey(resourceKey);

        long deadline = clock.millis() + request.getWaitTimeoutMs();
        long attempt = 0;
        long contentionStart = System.nanoTime();

        Observation observation = Observation.createNotStarted("lock.acquire", observationRegistry)
                .lowCardinalityKeyValue("backend", properties.getBackend())
                .lowCardinalityKeyValue("result", "pending")
                .highCardinalityKeyValue("resource_group", extractResourceGroup(resourceKey))
                .start();

        log.info("operation=acquire_with_timeout resource_key_hash={} owner_id={} lease_ms={} wait_timeout_ms={} backend={}",
                keyHash, ownerId, leaseMs, request.getWaitTimeoutMs(), properties.getBackend());

        try {
            while (clock.millis() < deadline) {
                metrics.recordAcquireAttempt();
                long start = System.nanoTime();

                boolean acquired = backend.acquire(key, token, leaseMs);
                metrics.recordAcquireDuration(System.nanoTime() - start);

                if (acquired) {
                    long expiresAt = clock.millis() + leaseMs;
                    metrics.recordAcquireSuccess();
                    metrics.recordContentionWait(System.nanoTime() - contentionStart);
                    observation.lowCardinalityKeyValue("result", "success")
                            .highCardinalityKeyValue("retry_count", String.valueOf(attempt));
                    log.info("operation=acquire_success resource_key_hash={} owner_id={} token={} lease_ms={} attempts={} backend={}",
                            keyHash, ownerId, token, leaseMs, attempt + 1, properties.getBackend());
                    return LockResult.success(resourceKey, token, leaseMs, expiresAt);
                }

                attempt++;
                long sleepMs = calculateBackoff(attempt);
                long remainingMs = deadline - clock.millis();
                sleepMs = Math.min(sleepMs, remainingMs);

                if (sleepMs <= 0) {
                    break;
                }

                Thread.sleep(sleepMs);
            }

            metrics.recordAcquireFailed();
            metrics.recordContentionWait(System.nanoTime() - contentionStart);
            observation.lowCardinalityKeyValue("result", "timeout")
                    .highCardinalityKeyValue("retry_count", String.valueOf(attempt));
            log.info("operation=acquire_timeout resource_key_hash={} owner_id={} attempts={} backend={}",
                    keyHash, ownerId, attempt, properties.getBackend());
            return LockResult.failure(resourceKey);
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Renews a lock's lease.
     *
     * @param resourceKey the resource key
     * @param token       the owner token
     * @param leaseMs     the new lease duration
     * @return {@code true} if renewed
     */
    public boolean renew(String resourceKey, String token, long leaseMs) {
        validateResourceKey(resourceKey);
        validateToken(token);
        String key = formatKey(resourceKey);
        String keyHash = hashResourceKey(resourceKey);

        Observation observation = Observation.createNotStarted("lock.renew", observationRegistry)
                .lowCardinalityKeyValue("backend", properties.getBackend())
                .lowCardinalityKeyValue("result", "pending")
                .highCardinalityKeyValue("resource_group", extractResourceGroup(resourceKey))
                .start();

        log.info("operation=renew_attempt resource_key_hash={} token={} lease_ms={} backend={}",
                keyHash, token, leaseMs, properties.getBackend());

        try {
            boolean renewed = backend.renew(key, token, leaseMs);

            if (renewed) {
                metrics.recordRenewSuccess();
                observation.lowCardinalityKeyValue("result", "success");
                log.info("operation=renew_success resource_key_hash={} token={} backend={}",
                        keyHash, token, properties.getBackend());
            } else {
                metrics.recordRenewFailed();
                observation.lowCardinalityKeyValue("result", "failed");
                log.warn("operation=renew_failed resource_key_hash={} token={} reason=not_owner_or_expired backend={}",
                        keyHash, token, properties.getBackend());
            }

            return renewed;
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Releases a lock.
     *
     * @param resourceKey the resource key
     * @param token       the owner token
     * @return {@code true} if released
     */
    public boolean release(String resourceKey, String token) {
        validateResourceKey(resourceKey);
        validateToken(token);
        String key = formatKey(resourceKey);
        String keyHash = hashResourceKey(resourceKey);

        Observation observation = Observation.createNotStarted("lock.release", observationRegistry)
                .lowCardinalityKeyValue("backend", properties.getBackend())
                .lowCardinalityKeyValue("result", "pending")
                .highCardinalityKeyValue("resource_group", extractResourceGroup(resourceKey))
                .start();

        log.info("operation=release_attempt resource_key_hash={} token={} backend={}",
                keyHash, token, properties.getBackend());

        try {
            boolean released = backend.release(key, token);

            if (released) {
                metrics.recordReleaseSuccess();
                observation.lowCardinalityKeyValue("result", "success");
                log.info("operation=release_success resource_key_hash={} token={} backend={}",
                        keyHash, token, properties.getBackend());
            } else {
                metrics.recordReleaseFailed();
                observation.lowCardinalityKeyValue("result", "failed");
                log.warn("operation=release_failed resource_key_hash={} token={} reason=not_owner_or_expired backend={}",
                        keyHash, token, properties.getBackend());
            }

            return released;
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Calculates exponential backoff with optional jitter from configuration.
     */
    long calculateBackoff(long attempt) {
        DistributedLockProperties.RetryConfig retry = properties.getRetry();
        long baseMs = retry.getInitialBackoffMs();
        long maxMs = retry.getMaxBackoffMs();

        long exponentialMs = Math.min(baseMs * (1L << Math.min(attempt, 10)), maxMs);

        if (retry.isJitter()) {
            long jitter = ThreadLocalRandom.current().nextLong(0, exponentialMs / 2 + 1);
            return exponentialMs / 2 + jitter;
        }

        return exponentialMs;
    }

    private long resolveLeaseMs(LockRequest request) {
        return request.getLeaseMs() > 0 ? request.getLeaseMs() : properties.getDefaultLeaseMs();
    }

    private String resolveOwnerId(LockRequest request) {
        if (request.getOwnerId() != null) {
            return request.getOwnerId();
        }
        return properties.getOwnerId() != null ? properties.getOwnerId() : "unknown";
    }

    /**
     * Extracts resource group from key for low-cardinality tracing.
     * For "job:daily-settlement" returns "job", for "resource:invoice:123" returns "resource".
     */
    private String extractResourceGroup(String resourceKey) {
        int idx = resourceKey.indexOf(':');
        return idx > 0 ? resourceKey.substring(0, idx) : resourceKey;
    }

    /**
     * Hashes resource key for structured logs to avoid leaking sensitive identifiers.
     */
    static String hashResourceKey(String resourceKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(resourceKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8); // first 8 bytes = 16 hex chars
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            return resourceKey;
        }
    }

    private void validateRequest(LockRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LockRequest must not be null");
        }
        if (request.getResourceKey() == null || request.getResourceKey().isBlank()) {
            throw new IllegalArgumentException("resourceKey must not be blank");
        }
    }

    private void validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
    }

    private void validateResourceKey(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("resourceKey must not be blank");
        }
    }

    private String formatKey(String resourceKey) {
        return "lock:" + resourceKey;
    }
}
