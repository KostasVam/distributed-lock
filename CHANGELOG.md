# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-03-16

### Added
- Redis backend with Lua scripts for atomic lock operations (acquire, release, renew)
- In-memory backend for local development and testing
- Lease-based lock expiration via Redis TTL
- UUID-based ownership tokens with atomic verification
- Fencing tokens (monotonically increasing via Redis INCR) for stale owner protection
- `tryAcquire` API for single-attempt lock acquisition
- `acquire` API with configurable timeout, exponential backoff, and jitter
- `executeWithLock` convenience API (acquire → execute → release in finally)
- `acquireWithAutoRenew` with `LockHandle` (AutoCloseable, background lease renewal)
- Graceful shutdown via `LockRegistry` (@PreDestroy releases all active locks)
- `@DistributedLock` annotation with SpEL key expressions and AOP support
- Configurable retry policy (initialBackoffMs, maxBackoffMs, jitter) via YAML
- Configurable fail-closed / fail-open on backend failure
- Configurable circuit breaker thresholds via YAML
- Configurable Redis command timeout (spring.data.redis.timeout)
- Global `ownerId` and `defaultLeaseMs` configuration
- Input validation on all API operations, annotations, and configuration properties
- `TokenGenerator` interface with `UuidTokenGenerator` default (pluggable)
- Health indicator for lock backend status (`/actuator/health`)
- Lua scripts distinguish key-not-found vs token-mismatch (return codes 0, 1, -1)
- Resilience4j circuit breaker around all Redis calls
- Prometheus metrics via Micrometer with low-cardinality labels (operation, result, backend)
- Fencing token gauge metric (`distributed_lock_fencing_token_latest`)
- Micrometer Observation tracing spans (lock.acquire, lock.renew, lock.release)
- Structured logging with SHA-256 hashed resource keys
- Spring Boot Starter packaging with auto-configuration (no component scanning required)
- Spring AOP integration for `@DistributedLock` annotation
- GitHub Actions CI pipeline with Redis service container
- Usage examples: batch-job, scheduler-singleton, leader-election
- Comprehensive test suite: unit, Lua contract, integration, annotation, auto-renew, fencing, chaos
- Architecture Decision Records (ADR-001 through ADR-008)
- Safety/threat model documentation
- Performance characteristics documentation
- Redis deployment guide (Standalone, Sentinel, Cluster)
- Grafana dashboard documentation with PromQL examples
- MIT License
