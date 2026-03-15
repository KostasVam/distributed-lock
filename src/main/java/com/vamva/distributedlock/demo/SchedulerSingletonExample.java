package com.vamva.distributedlock.demo;

import com.vamva.distributedlock.annotation.DistributedLock;
import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.engine.LockHandle;
import com.vamva.distributedlock.model.LockRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Example: Singleton scheduler using auto-renewing lock.
 *
 * <p>Demonstrates two approaches:</p>
 * <ul>
 *   <li>Programmatic: {@code acquireWithAutoRenew} for long-running tasks</li>
 *   <li>Declarative: {@code @DistributedLock} annotation for simple methods</li>
 * </ul>
 */
@Slf4j
public class SchedulerSingletonExample {

    private final DistributedLockClient lockClient;

    public SchedulerSingletonExample(DistributedLockClient lockClient) {
        this.lockClient = lockClient;
    }

    /**
     * Long-running task with auto-renewal.
     * The lock lease is automatically renewed every 2/3 of the lease duration.
     */
    public void longRunningExport() throws InterruptedException {
        try (LockHandle handle = lockClient.acquireWithAutoRenew(LockRequest.builder()
                .resourceKey("scheduler:data-export")
                .leaseMs(30_000) // 30s lease, auto-renewed every ~20s
                .waitTimeoutMs(5_000)
                .build())) {

            log.info("Acquired export lock (fence={})", handle.getFencingToken());

            // Simulate long-running work — lock is auto-renewed
            for (int batch = 1; batch <= 100; batch++) {
                // ... export batch ...
                Thread.sleep(1_000); // simulates work
                log.info("Exported batch {}/100", batch);
            }
        }
        // Lock released automatically on close
    }

    /**
     * Simple method with declarative locking via annotation.
     * No manual acquire/release needed.
     */
    @DistributedLock(key = "'scheduler:notifications'", leaseMs = 60_000)
    public void sendNotifications() {
        log.info("Sending notifications (only one instance runs this)");
        // ... notification logic ...
    }
}
