# ADR-007: Fencing Tokens for Stale Owner Protection

**Status:** Accepted
**Date:** 2026-03-16

## Context

A lease-based lock cannot prevent a stale owner from continuing to operate after its lease expired. If Process A acquires a lock, pauses (GC, network), the lease expires, Process B acquires the lock, and then Process A resumes — both believe they hold the lock.

This is the fundamental "stale owner problem" in distributed locking.

## Decision

Each lock acquisition returns a monotonically increasing **fencing token**. This token is generated atomically alongside the lock acquisition using Redis `INCR` on a dedicated counter key (`lock:{resource}:fence`).

Downstream systems can use this token to reject stale writes:
- Accept a write only if its fencing token is greater than the last accepted token
- Reject writes with lower or equal fencing tokens

## Implementation

The acquire Lua script atomically performs:
1. `SET key token NX PX leaseMs` — acquire the lock
2. `INCR fence_key` — increment the fencing counter

Both operations execute in a single Lua script, ensuring the fencing token is always assigned to the actual lock holder.

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **No fencing** | Simpler | Stale owner can corrupt data |
| **Logical clock (Lamport)** | Covers more cases | Complex, requires all participants to track |
| **Redis INCR counter** (chosen) | Simple, atomic, monotonic | Requires downstream cooperation |

## Consequences

### Positive

- Provides a mechanism for downstream systems to detect stale lock holders
- Atomic with acquisition — no separate round trip
- Monotonically increasing — simple comparison semantics
- No additional infrastructure required (uses existing Redis)

### Negative

- Downstream systems must explicitly check the fencing token — not automatic
- Fencing counter key persists in Redis (never expires) — small memory overhead
- Only works if downstream systems cooperate

### Operational Guidance

- Pass `LockResult.getFencingToken()` to downstream writes
- Downstream should reject writes where `fencingToken <= lastAcceptedToken`
- This is a defense-in-depth measure, not a replacement for idempotent operations
