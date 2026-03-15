# ADR-002: Use Lease-Based Locking Instead of Permanent Locks

**Status:** Accepted
**Date:** 2026-03-15

## Context

Distributed locks need a mechanism to handle the case where the lock holder crashes without releasing. Permanent locks would remain indefinitely, blocking all other clients.

## Decision

Use lease-based locking: every lock acquisition includes a TTL (lease duration). The lock expires automatically if not renewed.

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **Permanent locks** | Simpler model | Orphaned locks block indefinitely |
| **Heartbeat with external monitor** | Can detect crashed holders | Complex, additional service needed |
| **Session-based (ZooKeeper ephemeral nodes)** | Automatic cleanup on disconnect | Requires ZooKeeper; adds dependency |

## Consequences

### Positive

- Orphaned locks are impossible — TTL guarantees automatic cleanup
- Simple model: acquire with TTL, renew to extend, release when done
- No external monitoring service needed

### Negative

- Stale owner risk: if lease expires during GC pause, another client can acquire the lock while the original owner continues operating
- Requires careful lease duration selection: too short = unnecessary expiration; too long = slow recovery

### Operational Guidance

- Default lease: 30 seconds
- Renew at 50-70% of lease interval for safety margin
- Use `executeWithLock` for automatic release in finally block
- Design downstream operations to be idempotent
