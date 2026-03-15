package com.vamva.distributedlock.engine;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registry of active lock handles for graceful shutdown and auto-renewal scheduling.
 *
 * <p>On application shutdown ({@link PreDestroy}), all registered locks are released
 * and the renewal scheduler is stopped. This prevents orphaned locks when the
 * application is gracefully terminated.</p>
 */
@Slf4j
public class LockRegistry {

    private final Set<LockHandle> activeLocks = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService renewalScheduler;

    public LockRegistry() {
        this.renewalScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "lock-auto-renew");
            t.setDaemon(true);
            return t;
        });
    }

    public void register(LockHandle handle) {
        activeLocks.add(handle);
    }

    public void unregister(LockHandle handle) {
        activeLocks.remove(handle);
    }

    public ScheduledExecutorService getRenewalScheduler() {
        return renewalScheduler;
    }

    /**
     * Releases all active locks and shuts down the renewal scheduler.
     * Called automatically on Spring application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        int count = activeLocks.size();
        if (count > 0) {
            log.info("Graceful shutdown: releasing {} active lock(s)", count);
        }

        for (LockHandle handle : activeLocks) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("Failed to release lock on shutdown: resource_key={} error={}",
                        handle.getResourceKey(), e.getMessage());
            }
        }

        renewalScheduler.shutdown();
        try {
            if (!renewalScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                renewalScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            renewalScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (count > 0) {
            log.info("Graceful shutdown: all locks released");
        }
    }
}
