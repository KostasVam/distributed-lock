package com.vamva.distributedlock.demo;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Example: Preventing duplicate batch job execution across instances.
 *
 * <p>Multiple instances of this service may run the same scheduler.
 * The distributed lock ensures only one instance executes the batch job.</p>
 *
 * <pre>{@code
 * // Enable with @Component and @EnableScheduling
 * }</pre>
 */
@Slf4j
public class BatchJobExample {

    private final DistributedLockClient lockClient;

    public BatchJobExample(DistributedLockClient lockClient) {
        this.lockClient = lockClient;
    }

    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    public void dailySettlement() {
        LockResult lock = lockClient.tryAcquire(LockRequest.builder()
                .resourceKey("job:daily-settlement")
                .leaseMs(300_000) // 5 minutes
                .build());

        if (!lock.isAcquired()) {
            log.info("Another instance is running daily-settlement, skipping");
            return;
        }

        try {
            log.info("Starting daily settlement (fence={})", lock.getFencingToken());
            // ... batch job logic ...
        } finally {
            lockClient.release(lock.getResourceKey(), lock.getLockToken());
            log.info("Daily settlement completed");
        }
    }
}
