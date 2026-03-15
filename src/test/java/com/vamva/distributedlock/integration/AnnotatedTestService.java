package com.vamva.distributedlock.integration;

import com.vamva.distributedlock.annotation.DistributedLock;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnnotatedTestService {

    private final AtomicInteger executionCount = new AtomicInteger(0);

    @DistributedLock(key = "'annotation:test:static-key'", leaseMs = 30000)
    public String staticKeyMethod() {
        executionCount.incrementAndGet();
        return "executed";
    }

    @DistributedLock(key = "'annotation:test:' + #id", leaseMs = 30000)
    public String spelKeyMethod(long id) {
        executionCount.incrementAndGet();
        return "executed-" + id;
    }

    @DistributedLock(key = "'annotation:test:slow'", leaseMs = 5000)
    public void slowMethod() throws InterruptedException {
        executionCount.incrementAndGet();
        Thread.sleep(200);
    }

    @DistributedLock(key = "'annotation:test:autorenew'", leaseMs = 1000, autoRenew = true)
    public String autoRenewMethod() throws InterruptedException {
        executionCount.incrementAndGet();
        Thread.sleep(2_500); // runs longer than lease — auto-renew keeps it alive
        return "completed";
    }

    @DistributedLock(key = "#name", leaseMs = 30000)
    public String nullableKeyMethod(String name) {
        executionCount.incrementAndGet();
        return "executed";
    }

    public int getExecutionCount() {
        return executionCount.get();
    }

    public void resetCount() {
        executionCount.set(0);
    }
}
