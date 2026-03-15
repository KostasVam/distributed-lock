package com.vamva.distributedlock.demo;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.api.LockAcquisitionException;
import com.vamva.distributedlock.engine.LockHandle;
import com.vamva.distributedlock.model.LockRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Example: Simple leader election using distributed lock.
 *
 * <p>One instance acquires a long-lived, auto-renewing lock to become the leader.
 * If the leader crashes, the lease expires and another instance takes over.</p>
 *
 * <p>This is best-effort leader election, not consensus-based.
 * Suitable for non-critical coordination tasks.</p>
 */
@Slf4j
public class LeaderElectionExample {

    private final DistributedLockClient lockClient;
    private volatile LockHandle leaderLock;

    public LeaderElectionExample(DistributedLockClient lockClient) {
        this.lockClient = lockClient;
    }

    /**
     * Attempts to become leader. Call this on application startup.
     * If another instance is already leader, this returns without blocking.
     */
    public boolean tryBecomeLeader() {
        try {
            leaderLock = lockClient.acquireWithAutoRenew(LockRequest.builder()
                    .resourceKey("leader:notification-service")
                    .leaseMs(15_000) // 15s lease, auto-renewed every ~10s
                    .build());
            log.info("This instance is now the LEADER (fence={})", leaderLock.getFencingToken());
            return true;
        } catch (LockAcquisitionException e) {
            log.info("Another instance is leader, running as FOLLOWER");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isLeader() {
        return leaderLock != null && leaderLock.isHeld();
    }

    /**
     * Relinquishes leadership. Called on application shutdown
     * (also handled automatically by LockRegistry graceful shutdown).
     */
    public void resign() {
        if (leaderLock != null) {
            leaderLock.close();
            leaderLock = null;
            log.info("Resigned as leader");
        }
    }
}
