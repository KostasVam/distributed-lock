# ADR-001: Use Redis as Lock Backend

**Status:** Accepted
**Date:** 2026-03-15

## Context

Distributed applications require shared lock state to enforce mutual exclusion across multiple instances. The backend must support atomic check-and-set operations, key expiration, and sub-millisecond latency.

## Decision

Use Redis as the primary lock backend, with Lua scripts for atomic ownership-safe operations.

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **In-memory only** | Zero latency, no dependency | Cannot enforce locks across instances |
| **Database (PostgreSQL)** | Durable, ACID transactions | High latency for per-request operations; row-level locking |
| **ZooKeeper** | Strong consistency via ZAB | Operational complexity; heavy dependency |
| **etcd** | Linearizable locks | Requires etcd cluster; operational overhead |

## Consequences

### Positive

- `SET NX PX` provides atomic acquire with TTL in a single command
- Native TTL support automatically cleans up expired locks
- Sub-millisecond latency on local network
- Battle-tested for distributed locking (Redlock paper, industry adoption)

### Negative

- Additional infrastructure dependency
- Not consensus-based: cannot guarantee strict mutual exclusion during network partitions
- Requires fail-closed decision when Redis is unavailable (see ADR-005)

### Mitigations

- In-memory backend for local development and testing
- Resilience4j circuit breaker prevents cascading failures
- Redis Sentinel or Cluster for high availability
