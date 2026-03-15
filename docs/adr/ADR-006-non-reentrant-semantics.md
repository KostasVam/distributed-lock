# ADR-006: Non-Reentrant Lock Semantics

**Status:** Accepted
**Date:** 2026-03-15

## Context

Reentrant locks allow the same owner to acquire the lock multiple times without blocking. This requires tracking acquisition count and only releasing when the count reaches zero. In a distributed system, this adds complexity to the ownership model.

## Decision

Implement non-reentrant locks for MVP. If the same caller (even with the same ownerId) attempts to acquire a lock it already holds, the acquisition fails.

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **Non-reentrant** (chosen) | Simpler semantics, safer correctness model | Less flexibility for recursive/nested lock usage |
| **Reentrant** | Allows nested acquire from same owner | Requires counter tracking, complicates ownership model, risk of forgotten release |

## Rationale

- Non-reentrant locks have simpler and more predictable semantics
- Reentrancy in distributed systems is inherently fragile (what is "same owner" across retries, threads, or instances?)
- Most lock use cases (batch jobs, schedulers, leader election) don't require reentrancy
- Reentrancy can be added as a future feature without breaking the existing API

## Consequences

### Positive

- Simple ownership model: one token, one holder
- No counter tracking or release-count matching
- Clear contract: acquire succeeds once, fails if already held

### Negative

- Cannot nest lock acquisitions for the same resource key
- Caller must be careful not to re-acquire a lock it already holds

### Future

Reentrant mode can be added by:
1. Storing `{token, count}` instead of just `token`
2. Incrementing count on re-acquire from same token
3. Decrementing on release; only deleting when count reaches 0
