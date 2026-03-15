# ADR-005: Default to Fail-Closed on Backend Failure

**Status:** Accepted
**Date:** 2026-03-15

## Context

When Redis is unavailable, the lock library must decide whether to allow or deny lock acquisition. This has significant safety implications — locks protect critical sections where duplicate execution can cause data corruption.

## Decision

Default to **fail-closed**: when Redis is unreachable, lock acquisition fails. The caller's protected operation does not execute.

## Alternatives Considered

| Mode | Behavior | Risk |
|---|---|---|
| **Fail-closed** (chosen) | Acquisition fails when backend is down | Operations blocked during Redis outage |
| **Fail-open** | Acquisition succeeds when backend is down | Multiple clients may execute protected section simultaneously |

## Rationale

Unlike rate limiting (where fail-open is acceptable because the consequence is temporary over-admission), distributed locks protect critical sections where dual execution can cause:

- Duplicate batch job execution
- Duplicate financial transactions
- Data corruption from concurrent writes

Failing open would defeat the purpose of the lock.

## Consequences

### Positive

- Lock guarantee is never silently violated
- Backend errors are observable via `distributed_lock_backend_errors_total`
- Circuit breaker prevents sustained latency from Redis timeouts

### Negative

- Protected operations are blocked during Redis outage
- Requires Redis HA (Sentinel/Cluster) for production reliability

### Operational Guidance

- **Monitor** `distributed_lock_backend_errors_total` and alert on sustained non-zero values
- **Fail-open** mode is available via `distributed-lock.fail-open: true` for non-critical lock use cases. When enabled, backend unavailability returns a synthetic success with `fencingToken=0`, indicating unverified acquisition. Use with extreme caution.
- Deploy Redis with Sentinel or Cluster for high availability
