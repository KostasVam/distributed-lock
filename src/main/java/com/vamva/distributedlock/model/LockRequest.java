package com.vamva.distributedlock.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Request to acquire a distributed lock.
 *
 * <p>Encapsulates the resource key, lease duration, optional wait timeout,
 * and an optional owner identifier for traceability.</p>
 */
@Getter
@Builder
public class LockRequest {

    /** The resource key to lock (e.g., "job:daily-settlement"). */
    private final String resourceKey;

    /**
     * Lease duration in milliseconds. Lock expires automatically after this period.
     * If zero or negative, the global default from {@code distributed-lock.default-lease-ms} is used.
     */
    @Builder.Default
    private final long leaseMs = 0;

    /**
     * Maximum time in milliseconds to wait for lock acquisition.
     * If zero or negative, only a single attempt is made (try-lock semantics).
     */
    @Builder.Default
    private final long waitTimeoutMs = 0;

    /** Optional owner identifier for logging and tracing. */
    private final String ownerId;
}
