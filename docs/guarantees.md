# Guarantees & Non-Guarantees

This library provides best-effort distributed mutual exclusion for a bounded problem space using lease-based locks backed by Redis.

It is designed for scenarios such as:

- singleton schedulers
- deduplicating batch/job execution
- leader-style coordination for non-critical tasks
- protecting coarse-grained shared resources
- reducing duplicate work across instances

It is **not** a consensus system and does not provide linearizable coordination guarantees.

## Core Model

A lock is represented by:

- a resource key
- an owner token
- a lease duration (TTL)
- an optional fencing token

Ownership is valid only while the lease is valid and the caller still holds the matching token.

If the lease expires, ownership is no longer guaranteed, even if the application thread is still running.

## What This Library Guarantees

### 1. Atomic acquire/release/renew semantics at the backend level

For the Redis backend, lock acquisition, renewal, and release are implemented using atomic Redis/Lua operations.

This guarantees that:

- a lock cannot be released by a different owner token
- a lock cannot be renewed by a different owner token
- acquisition and lease assignment happen atomically

### 2. Token-based ownership validation

The library uses ownership tokens to ensure that only the current lock holder can renew or release a lock.

This prevents accidental unlock/renew actions by non-owners.

### 3. Lease-bounded ownership

Lock ownership is only considered valid for the configured lease interval.

This means the library provides time-bounded exclusion, not permanent exclusion.

### 4. Explicit acquisition outcomes

The library distinguishes between different acquisition results:

| Outcome | Meaning |
|---|---|
| `ACQUIRED` | Lock acquired with verified ownership |
| `CONTENDED` | Lock held by another client |
| `TIMEOUT` | Retry budget exhausted |
| `BACKEND_UNAVAILABLE` | Redis/circuit breaker down, fail-closed |
| `FAIL_OPEN_SYNTHETIC` | Backend down, synthetic success (unverified) |

This allows consumers to react differently to contention, infrastructure degradation, and degraded fail-open mode.

### 5. Explicit degraded-mode signaling in fail-open mode

When configured for fail-open behavior, backend acquisition failure does not silently masquerade as normal ownership.

Instead, the caller receives an explicit degraded result (`FAIL_OPEN_SYNTHETIC`) and can detect that ownership is not verified via `LockResult.isVerifiedOwnership() == false`.

### 6. Safe-by-default annotation path for long-running work

The annotation-based integration is designed to reduce accidental misuse for long-running critical sections. The default `autoRenew=true` ensures lease renewal during execution, but callers can opt out with `autoRenew=false` for short critical sections where the lease duration is guaranteed to exceed method execution time.

### 7. Fencing-token support for downstream stale-owner protection

Where fencing tokens are enabled and enforced by downstream systems, a stale owner can be rejected even if it continues running after lease loss.

This is the primary mitigation against split-brain style stale-writer behavior.

## What This Library Does Not Guarantee

### 1. Strict global mutual exclusion under all failure conditions

This library does not guarantee perfect mutual exclusion in the presence of:

- long GC pauses
- stop-the-world pauses
- network partitions
- Redis failover edge cases
- severe clock/timing distortions
- process suspension or delayed scheduling

Lease-based locking reduces duplicate execution risk, but cannot eliminate it in all distributed failure scenarios.

### 2. Consensus or linearizability

This library is not a replacement for:

- ZooKeeper
- etcd
- Consul sessions
- Raft-based coordination systems

If your use case requires strong distributed consensus semantics, this library is the wrong tool.

### 3. Safe execution after lease expiry

If the lease expires while application code is still running, the library cannot make that code safe automatically.

At that point, ownership may already have been lost and another actor may have acquired the same logical lock.

### 4. Downstream stale-writer protection unless consumers enforce it

Fencing tokens are only useful if downstream systems actually validate them.

If a consumer ignores fencing tokens, stale owners may still perform unsafe writes after lease loss.

### 5. Business-level idempotency

This library coordinates execution ownership. It does not replace:

- idempotency keys
- transactional outbox patterns
- deduplication tables
- exactly-once business semantics

If duplicate side effects are unacceptable, additional application-level protections are still required.

### 6. Safety in fail-open mode

Fail-open mode prioritizes availability over verified exclusivity.

When fail-open is enabled, the system may continue execution during backend failure, but this must be treated as degraded, non-verified ownership.

Fail-open is therefore a **business decision**, not just a technical toggle.

## Scenario Matrix

| Scenario | What the library guarantees | What it does not guarantee | What the consumer should do |
|---|---|---|---|
| Normal acquire succeeds | Caller receives verified ownership for the lease duration | Ownership beyond lease expiry | Keep work within lease or use auto-renew |
| Another owner already holds the lock | Caller receives `CONTENDED` instead of false ownership | Fairness or global ordering between contenders | Retry according to business needs |
| Wait timeout expires | Caller receives `TIMEOUT` explicitly | Eventual success | Decide whether to abort, retry later, or surface backpressure |
| Backend unavailable, fail-closed | Caller receives `BACKEND_UNAVAILABLE` / no lock granted | Continued availability | Fail safely, reschedule, or degrade the workflow explicitly |
| Backend unavailable, fail-open | Caller receives explicit degraded synthetic result | Verified exclusivity | Only proceed for low-risk workloads that tolerate degraded coordination |
| Lease expires during execution | Ownership is no longer considered valid | Safe continuation of in-flight work | Stop work, design for idempotency, or enforce fencing downstream |
| Renew fails and handle becomes lost | Loss of ownership can be surfaced explicitly via `onLockLost()` | Automatic recovery of correctness | Abort or compensate, depending on workload type |
| Process crashes after acquire | Lease will eventually expire and lock can be reacquired | Immediate cleanup | Keep lease short enough for recovery objectives |
| Stale owner continues after new owner acquires lock | Fencing tokens can help reject stale writes downstream | Automatic stale-write prevention without downstream checks | Enforce fencing token validation in storage or downstream service |

## Consumer Responsibilities

To use this library safely, consumers should:

1. **Treat locks as leases**, not permanent ownership.
2. **Keep critical sections as short as possible.**
3. **Use auto-renew** for long-running guarded work.
4. **Enforce fencing tokens downstream** when stale-writer risk matters.
5. **Combine locking with idempotency** for business-critical side effects.
6. **Choose fail-open only** for workflows that can tolerate degraded exclusivity.
7. **Observe metrics/logs/traces** and respond to degraded states operationally.

## Practical Rule of Thumb

**Use this library when:**

> "Duplicate execution is undesirable and should be reduced aggressively, but correctness can still be defended with bounded leases, downstream validation, and idempotent design."

**Do not use this library when:**

> "A duplicate or stale execution would be catastrophic and no downstream safeguard exists."
