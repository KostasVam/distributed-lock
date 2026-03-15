package com.vamva.distributedlock.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus metrics instrumentation for the distributed lock library.
 *
 * <p>Uses low-cardinality labels only ({@code operation}, {@code result}, {@code backend})
 * to prevent metric cardinality explosion from dynamic resource keys.</p>
 *
 * <p>Exposed metrics:</p>
 * <ul>
 *   <li>{@code distributed_lock_acquire_attempts_total} — total acquire attempts (backend)</li>
 *   <li>{@code distributed_lock_acquire_success_total} — successful acquisitions (backend)</li>
 *   <li>{@code distributed_lock_acquire_failed_total} — failed acquisitions (backend)</li>
 *   <li>{@code distributed_lock_release_success_total} — successful releases (backend)</li>
 *   <li>{@code distributed_lock_release_failed_total} — failed releases (backend)</li>
 *   <li>{@code distributed_lock_renew_success_total} — successful renewals (backend)</li>
 *   <li>{@code distributed_lock_renew_failed_total} — failed renewals (backend)</li>
 *   <li>{@code distributed_lock_backend_errors_total} — backend failures (backend)</li>
 *   <li>{@code distributed_lock_acquire_duration_ms} — acquisition time histogram (backend)</li>
 *   <li>{@code distributed_lock_contention_wait_ms} — contention wait time histogram (backend)</li>
 * </ul>
 */
public class LockMetrics {

    private final MeterRegistry registry;
    private final String backendType;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> fencingGauges = new ConcurrentHashMap<>();

    public LockMetrics(MeterRegistry registry, String backendType) {
        this.registry = registry;
        this.backendType = backendType;
    }

    public void recordAcquireAttempt() {
        getOrCreateCounter("distributed_lock_acquire_attempts_total", "acquire", null).increment();
    }

    public void recordAcquireSuccess() {
        getOrCreateCounter("distributed_lock_acquire_success_total", "acquire", "success").increment();
    }

    public void recordAcquireFailed() {
        getOrCreateCounter("distributed_lock_acquire_failed_total", "acquire", "failed").increment();
    }

    public void recordReleaseSuccess() {
        getOrCreateCounter("distributed_lock_release_success_total", "release", "success").increment();
    }

    public void recordReleaseFailed() {
        getOrCreateCounter("distributed_lock_release_failed_total", "release", "failed").increment();
    }

    public void recordRenewSuccess() {
        getOrCreateCounter("distributed_lock_renew_success_total", "renew", "success").increment();
    }

    public void recordRenewFailed() {
        getOrCreateCounter("distributed_lock_renew_failed_total", "renew", "failed").increment();
    }

    public void recordAcquireDuration(long nanos) {
        getOrCreateTimer("distributed_lock_acquire_duration_ms",
                "Time spent acquiring locks").record(Duration.ofNanos(nanos));
    }

    public void recordContentionWait(long nanos) {
        getOrCreateTimer("distributed_lock_contention_wait_ms",
                "Time spent waiting for lock acquisition under contention").record(Duration.ofNanos(nanos));
    }

    public void recordFencingToken(String resourceKey, long fencingToken) {
        java.util.concurrent.atomic.AtomicLong gauge = fencingGauges.computeIfAbsent(resourceKey, k -> {
            java.util.concurrent.atomic.AtomicLong value = new java.util.concurrent.atomic.AtomicLong(0);
            Gauge.builder("distributed_lock_fencing_token_latest", value, java.util.concurrent.atomic.AtomicLong::doubleValue)
                    .description("Latest fencing token value per resource key")
                    .tag("backend", backendType)
                    .register(registry);
            return value;
        });
        gauge.set(fencingToken);
    }

    public void recordBackendError() {
        getOrCreateCounter("distributed_lock_backend_errors_total", "backend_error", null).increment();
    }

    private Counter getOrCreateCounter(String name, String operation, String result) {
        String cacheKey = name + ":" + operation + ":" + result;
        return counterCache.computeIfAbsent(cacheKey, k -> {
            Counter.Builder builder = Counter.builder(name)
                    .tag("operation", operation)
                    .tag("backend", backendType);
            if (result != null) {
                builder.tag("result", result);
            }
            return builder.register(registry);
        });
    }

    private Timer getOrCreateTimer(String name, String description) {
        return timerCache.computeIfAbsent(name, k ->
                Timer.builder(name)
                        .description(description)
                        .tag("backend", backendType)
                        .register(registry));
    }
}
