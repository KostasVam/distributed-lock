# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-03-15

### Added
- Redis backend with Lua scripts for atomic lock operations (acquire, release, renew)
- In-memory backend for local development and testing
- Lease-based lock expiration via Redis TTL
- UUID-based ownership tokens with atomic verification
- `tryAcquire` API for single-attempt lock acquisition
- `acquire` API with configurable timeout, exponential backoff, and jitter
- `executeWithLock` convenience API (acquire → execute → release in finally)
- Configurable retry policy (initialBackoffMs, maxBackoffMs, jitter) via YAML
- Configurable fail-closed / fail-open on backend failure
- Global `ownerId` and `defaultLeaseMs` configuration
- Resilience4j circuit breaker around all Redis calls
- Prometheus metrics via Micrometer with low-cardinality labels (operation, result, backend)
- Micrometer Observation tracing spans (lock.acquire, lock.renew, lock.release)
- Structured logging with SHA-256 hashed resource keys
- Spring Boot Starter packaging with auto-configuration (no component scanning required)
- Comprehensive test suite: unit tests, Lua script contract tests, integration tests with Testcontainers
- Architecture Decision Records (ADR-001 through ADR-006)
- Safety/threat model documentation
- Performance characteristics documentation
- Redis deployment guide (Standalone, Sentinel, Cluster)
- Grafana dashboard documentation with PromQL examples
