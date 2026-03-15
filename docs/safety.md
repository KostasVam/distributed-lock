# Safety Considerations & Threat Model

## Consistency Model

The distributed lock provides **best-effort mutual exclusion** under a lease model. It does **not** provide linearizable guarantees in the presence of network partitions.

### What This Means

- The system guarantees that only one `SET NX` succeeds at any given moment in Redis
- But it cannot prevent a stale owner from continuing to operate after its lease expired
- This is a fundamental limitation of lease-based distributed locking, not a bug

### When This Matters

| Scenario | Risk | Mitigation |
|---|---|---|
| GC pause > lease duration | Stale owner continues after expiry | Short leases + frequent renew + fencing tokens |
| Network partition | Owner loses Redis connectivity, lease expires | Fencing tokens (pass to downstream) |
| Clock skew | Lease duration perceived differently | Use Redis server TTL, not client clock |

## Threat Model

### 1. Stale Owner Problem

**Threat:** Process A acquires lock, pauses (GC, scheduling delay), lease expires, Process B acquires lock, Process A resumes and continues writing — both think they hold the lock.

**Impact:** Dual execution of protected critical section.

**Mitigations:**
- Short lease durations (10-30s) with frequent renewal
- Design downstream operations to be idempotent
- Fencing tokens: each acquisition returns a monotonically increasing token; downstream systems reject writes with stale tokens (see `LockResult.getFencingToken()`)
- Check lock ownership before committing results

**Residual risk:** Medium — inherent to lease-based systems.

### 2. Forgotten Release

**Threat:** Developer acquires lock but forgets to release it, or exception path skips release.

**Impact:** Lock held until lease expires, blocking other clients.

**Mitigations:**
- Use `executeWithLock` (release in finally block)
- Lease TTL provides automatic cleanup
- Monitor `distributed_lock_acquire_failed_total` for sustained failures on a key
- Enforce maximum lease duration in configuration

**Residual risk:** Low — TTL is the safety net.

### 3. Excessively Long Leases

**Threat:** Developer configures very long lease (e.g., 1 hour) without renewal. If process crashes, the lock is orphaned for the entire duration.

**Impact:** Resource blocked for extended period.

**Mitigations:**
- Document recommended lease durations (10-60s)
- Use `defaultLeaseMs` in configuration to enforce organization defaults
- Auto-renew helper (planned for v2) for long-running tasks

**Residual risk:** Low — operational discipline.

### 4. Redis Unavailability

**Threat:** Redis goes down. Lock acquisitions fail.

**Impact:** Protected operations cannot proceed.

**Mitigations:**
- Circuit breaker prevents cascading failures (trips after 50% failure rate)
- Fail-closed default: operations safely skip rather than run unprotected
- Redis Sentinel or Cluster for HA
- Monitor `distributed_lock_backend_errors_total`

**Residual risk:** Low — by design (fail-closed).

### 5. Resource Key Collision

**Threat:** Two unrelated operations use the same lock key (e.g., both use `"job:process"`), causing unintended mutual exclusion.

**Impact:** Unrelated operations block each other.

**Mitigations:**
- Follow naming convention: `lock:{domain}:{entity}:{id}`
- Document resource naming strategy
- Use specific keys: `lock:job:daily-settlement` not `lock:job`

**Residual risk:** Low — naming discipline.

## Safety Notes

Distributed locks are **coordination primitives**, not **correctness guarantees**.

Applications using this library should:

1. Design idempotent operations wherever possible
2. Avoid relying solely on locking for data integrity
3. Consider fencing tokens for critical write paths
4. Monitor lock metrics and set up alerting
5. Use short leases with renewal for long-running work

## Security Checklist

| Item | Status |
|---|---|
| Redis not exposed to public network | Deployment responsibility |
| Redis AUTH enabled | Deployment responsibility |
| Resource keys hashed in logs | Implemented (SHA-256) |
| Fail-closed default | Implemented |
| Atomic ownership checks (Lua scripts) | Implemented |
| Circuit breaker on Redis calls | Implemented |
| Lock expiration via TTL | Implemented |
