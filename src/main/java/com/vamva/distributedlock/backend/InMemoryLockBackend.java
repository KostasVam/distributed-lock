package com.vamva.distributedlock.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory lock backend for single-instance, development, and test use.
 *
 * <p>Stores lock entries in a {@link ConcurrentHashMap} with TTL-based expiration.
 * Expired entries are cleaned up every 60 seconds via a scheduled task.</p>
 *
 * <p><strong>Warning:</strong> Locks are not shared across application instances.
 * Use {@link RedisLockBackend} for distributed deployments.</p>
 *
 * <p><strong>Concurrency:</strong> Uses synchronized blocks per lock key to guarantee
 * atomic check-and-set semantics. This backend prioritizes correctness over
 * maximum throughput.</p>
 */
@Slf4j
public class InMemoryLockBackend implements LockBackend {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryLockBackend() {
        this(Clock.systemUTC());
    }

    public InMemoryLockBackend(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean acquire(String key, String token, long leaseMs) {
        long now = clock.millis();
        boolean[] acquired = {false};

        locks.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt <= now) {
                acquired[0] = true;
                return new LockEntry(token, now + leaseMs);
            }
            return existing;
        });

        return acquired[0];
    }

    @Override
    public boolean release(String key, String token) {
        long now = clock.millis();
        boolean[] released = {false};

        locks.computeIfPresent(key, (k, existing) -> {
            if (existing.token.equals(token) && existing.expiresAt > now) {
                released[0] = true;
                return null; // remove
            }
            return existing;
        });

        return released[0];
    }

    @Override
    public boolean renew(String key, String token, long leaseMs) {
        long now = clock.millis();
        boolean[] renewed = {false};

        locks.computeIfPresent(key, (k, existing) -> {
            if (existing.token.equals(token) && existing.expiresAt > now) {
                renewed[0] = true;
                return new LockEntry(token, now + leaseMs);
            }
            return existing;
        });

        return renewed[0];
    }

    /**
     * Removes expired lock entries to prevent memory leaks.
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanup() {
        long now = clock.millis();
        int removed = 0;

        var iterator = locks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} expired lock entries", removed);
        }
    }

    static class LockEntry {
        final String token;
        final long expiresAt;

        LockEntry(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}
