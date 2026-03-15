# Distributed Lock

[![CI](https://github.com/KostasVam/distributed-lock/actions/workflows/ci.yml/badge.svg)](https://github.com/KostasVam/distributed-lock/actions/workflows/ci.yml)
[![Java 17](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-green.svg)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A lease-based distributed lock library with Redis-backed coordination, safe ownership semantics, and built-in observability for Spring Boot.

## Overview

A Spring Boot library that provides distributed mutual exclusion using Redis as a shared lock store. Locks are lease-based — they expire automatically via TTL, preventing orphaned locks when processes crash. Only the lock owner (verified by token) can release or renew a lock.

## Tech Stack

| Component       | Choice                           |
|-----------------|----------------------------------|
| Language        | Java 17                          |
| Framework       | Spring Boot 3.4                  |
| Build           | Gradle (Kotlin DSL)              |
| Redis Client    | Lettuce (via spring-data-redis)  |
| Metrics         | Micrometer + Prometheus          |
| Tracing         | Micrometer Observation API       |
| Config          | YAML (Spring native)             |
| Resilience      | Resilience4j (circuit breaker)   |
| Boilerplate     | Lombok                           |
| Testing         | JUnit 5 + Testcontainers         |
| Containerization| Docker Compose (Redis)           |

## Features (MVP)

- [x] Redis-backed lock acquisition (atomic via Lua scripts)
- [x] Lease-based expiration (TTL prevents orphaned locks)
- [x] Ownership token (UUID-based, required for release/renew)
- [x] Atomic safe release (Lua script: verify owner then delete)
- [x] Atomic safe renew (Lua script: verify owner then extend TTL)
- [x] `tryAcquire` API (single attempt, immediate return)
- [x] `acquire` with timeout (exponential backoff + jitter)
- [x] `executeWithLock` helper (acquire → execute → release in finally)
- [x] In-memory backend for local dev and testing
- [x] Configurable fail-closed / fail-open on Redis failure
- [x] Configurable retry policy (initialBackoff, maxBackoff, jitter)
- [x] Prometheus metrics via Micrometer (low-cardinality labels)
- [x] Micrometer Observation tracing spans
- [x] Structured logging with hashed resource keys
- [x] Resilience4j circuit breaker around Redis calls (configurable thresholds)
- [x] Spring Boot Starter packaging (auto-configuration, no component scanning)
- [x] Global `ownerId` and `defaultLeaseMs` configuration
- [x] Input validation on all API operations
- [x] Health indicator for backend status (`/actuator/health`)
- [x] `TokenGenerator` interface for pluggable token strategies
- [x] Lua scripts distinguish key-not-found vs token-mismatch
- [x] Bean Validation on configuration properties (`@Min`, `@Valid`)
- [x] Chaos tests (Redis stop/pause/recovery with circuit breaker)
- [x] Fencing tokens (monotonically increasing via Redis INCR)
- [x] Auto-renew helper (`LockHandle` with background renewal)
- [x] Graceful shutdown (automatic lock release via `LockRegistry` @PreDestroy)
- [x] `@DistributedLock` annotation with SpEL key expressions (AOP)
- [x] Usage examples (batch-job, scheduler-singleton, leader-election)

## Architecture

### Lock Lifecycle

```
FREE
  │
  │ acquire (SET NX PX)
  ▼
HELD
  │
  ├── renew (PEXPIRE) → HELD (lease extended)
  │
  ├── release (DEL) → FREE
  │
  └── lease expiry → FREE
```

### Acquire Flow

```
Client
  │
  │ tryAcquire(LockRequest)
  │
  ▼
LockEngine
  │
  │ generate token
  │ SET lock:{key} {token} NX PX {leaseMs}
  │
  ▼
Redis
  │
  │ OK (acquired) / nil (already held)
  │
  ▼
LockEngine
  │
  │ return LockResult
  ▼
Client
```

### Release Flow

```
Client
  │
  │ release(resourceKey, token)
  │
  ▼
LockEngine
  │
  │ Lua script:
  │   if GET(key) == token
  │     DEL(key)
  │
  ▼
Redis
```

### Component Overview

```
┌────────────────────────────────────────────────────────────────┐
│                     Spring Boot Application                     │
│                                                                 │
│  ┌─────────────────────┐                                        │
│  │ DistributedLockClient│  ← Public API (tryAcquire, acquire,  │
│  │                     │     renew, release, executeWithLock)   │
│  └──────────┬──────────┘                                        │
│             │                                                    │
│  ┌──────────▼──────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │    LockEngine       │─▶│ LockBackend  │─▶│ Redis / Memory │  │
│  │  (retry, backoff,   │  │ (interface)  │  │               │  │
│  │   tracing, logging) │  └──────────────┘  └───────────────┘  │
│  └──────────┬──────────┘                                        │
│             │                                                    │
│  ┌──────────▼──────────┐  ┌──────────────┐                     │
│  │  TokenGenerator     │  │  LockMetrics │                     │
│  │  (UUID)             │  │  (Micrometer)│                     │
│  └─────────────────────┘  └──────────────┘                     │
└────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| **DistributedLockClient** | Public API facade. Delegates to LockEngine. Provides `executeWithLock` convenience method with automatic release in finally block. |
| **LockEngine** | Orchestrates acquisition with retry logic, renewal, and release. Manages tracing spans, structured logging, and metrics recording. Resolves defaults from configuration. |
| **LockBackend** | Abstract interface for storage. Provides `acquire`, `release`, `renew` operations. Two implementations: `RedisLockBackend` and `InMemoryLockBackend`. |
| **RedisLockBackend** | Executes Lua scripts via `StringRedisTemplate`. Wrapped in Resilience4j circuit breaker. Fail-closed by default. |
| **InMemoryLockBackend** | ConcurrentHashMap-based lock store with TTL expiration and scheduled cleanup. For dev/test only. |
| **TokenGenerator** | Generates UUID-based owner tokens for lock ownership verification. |
| **LockMetrics** | Prometheus counters and histograms with low-cardinality labels (`operation`, `result`, `backend`). |
| **DistributedLockProperties** | YAML-bound configuration: backend, failOpen, defaultLeaseMs, ownerId, retry policy. |
| **DistributedLockAutoConfiguration** | Spring Boot auto-configuration. All beans declared explicitly with `@ConditionalOnMissingBean` for consumer overrides. |

## Consistency Model

The distributed lock provides **best-effort mutual exclusion** under a lease model.

### Guarantees

- Only one client can successfully acquire a lock at a given moment in Redis
- Ownership is validated via token matching on every release/renew
- Locks expire automatically via lease TTL

### Limitations

Strict mutual exclusion is **not guaranteed** during:

- Network partitions
- Process pauses (GC stop-the-world)
- Long scheduling delays
- Redis failover scenarios

This is inherent to lease-based distributed locking.

### Mitigation Strategies

- Short leases with frequent renewal
- Fencing tokens (future feature)
- Idempotent downstream operations
- Design critical sections to tolerate duplicate execution

## Configuration

```yaml
distributed-lock:
  enabled: true
  fail-open: false                    # fail-closed: acquisition fails when Redis is down
  backend: redis                      # redis | in-memory
  default-lease-ms: 30000            # fallback when LockRequest doesn't specify
  owner-id: ${spring.application.name:my-service}
  retry:
    enabled: true
    initial-backoff-ms: 50
    max-backoff-ms: 2000
    jitter: true
```

## Observability

### Prometheus Metrics

| Metric | Type | Labels |
|---|---|---|
| `distributed_lock_acquire_attempts_total` | Counter | `operation`, `backend` |
| `distributed_lock_acquire_success_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_acquire_failed_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_release_success_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_release_failed_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_renew_success_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_renew_failed_total` | Counter | `operation`, `result`, `backend` |
| `distributed_lock_backend_errors_total` | Counter | `operation`, `backend` |
| `distributed_lock_acquire_duration_ms` | Timer | `backend` |
| `distributed_lock_contention_wait_ms` | Timer | `backend` |

Metrics endpoint: `GET /actuator/prometheus`

### Structured Logs

Each lock operation logs with hashed resource keys (privacy-preserving):

```
operation=acquire_success resource_key_hash=a1b2c3d4e5f6g7h8 owner_id=batch-ms-1 token=uuid lease_ms=30000 backend=redis
```

### Tracing Spans

| Span | Attributes |
|---|---|
| `lock.acquire` | `backend`, `result`, `resource_group`, `retry_count` |
| `lock.renew` | `backend`, `result`, `resource_group` |
| `lock.release` | `backend`, `result`, `resource_group` |

## Failure Modes

| Scenario | Behavior |
|---|---|
| Process crash after acquire | Lock expires automatically via lease TTL |
| Process pause (GC) | Lock may expire; another client can acquire. Owner may continue unaware. |
| Redis unavailable | Acquisition fails (fail-closed default). Configurable fail-open. |
| Lost release (crash before release) | Lease TTL eventually frees the lock |
| Non-owner release attempt | Lua script verifies token; release safely rejected |

## Resource Key Naming Convention

```
lock:{domain}:{entity}:{id}
```

Examples:
```
lock:job:daily-settlement
lock:resource:invoice:12345
lock:leader:notification-service
lock:scheduler:commissions-clearing
```

## Acceptance Criteria

| ID | Scenario | Expected | Tested By |
|---|---|---|---|
| AC-1 | Free lock can be acquired | Acquisition succeeds with owner token | `LockEngineTest`, `DistributedLockIntegrationTest` |
| AC-2 | Second client cannot acquire held lock | Acquisition fails | `LockEngineTest`, `DistributedLockIntegrationTest` |
| AC-3 | Owner can release lock | Lock removed successfully | `LockEngineTest`, `DistributedLockIntegrationTest` |
| AC-4 | Non-owner cannot release lock | Release fails, lock intact | `LockEngineTest`, `DistributedLockIntegrationTest` |
| AC-5 | Owner can renew lock | TTL extended | `LockEngineTest`, `DistributedLockIntegrationTest` |
| AC-6 | Expired lock becomes acquirable | New client succeeds | `InMemoryLockBackendTest`, `DistributedLockIntegrationTest` |
| AC-7 | Backend failure fails safely | Acquisition fails, not assumed | `RedisLockBackend` (circuit breaker) |
| AC-8 | Concurrent acquisition: single winner | Exactly one succeeds | `DistributedLockIntegrationTest` |

## Usage as Library

Add the dependency to your Spring Boot project:

```kotlin
// build.gradle.kts
implementation("com.vamva:distributed-lock:0.1.0")
```

Auto-configuration activates automatically. No `@ComponentScan` needed.

```java
@Autowired
private DistributedLockClient lockClient;

// Try-lock (single attempt)
LockResult result = lockClient.tryAcquire(LockRequest.builder()
        .resourceKey("job:daily-settlement")
        .leaseMs(30_000)
        .build());

if (result.isAcquired()) {
    try {
        // do work
    } finally {
        lockClient.release(result.getResourceKey(), result.getLockToken());
    }
}

// Or use the convenience API
lockClient.executeWithLock(
    LockRequest.builder()
        .resourceKey("job:daily-settlement")
        .leaseMs(30_000)
        .waitTimeoutMs(5_000)
        .build(),
    () -> {
        // protected work here
        return result;
    }
);
```

All beans can be overridden by declaring your own (e.g., custom `LockBackend`, custom `TokenGenerator`).

## Getting Started

### Prerequisites
- Java 17+
- Docker (for Redis and integration tests)

### Run
```bash
# Start Redis
docker compose up -d

# Run tests (Docker required for integration tests)
./gradlew test

# View metrics
curl http://localhost:8080/actuator/prometheus | grep distributed_lock
```

## Project Structure

```
distributed-lock/
├── src/
│   ├── main/java/com/vamva/distributedlock/
│   │   ├── DistributedLockApplication.java
│   │   ├── api/
│   │   │   ├── DistributedLockClient.java
│   │   │   └── LockAcquisitionException.java
│   │   ├── backend/
│   │   │   ├── LockBackend.java
│   │   │   ├── RedisLockBackend.java
│   │   │   └── InMemoryLockBackend.java
│   │   ├── config/
│   │   │   ├── DistributedLockAutoConfiguration.java
│   │   │   ├── DistributedLockProperties.java
│   │   │   └── DistributedLockHealthIndicator.java
│   │   ├── engine/
│   │   │   └── LockEngine.java
│   │   ├── metrics/
│   │   │   └── LockMetrics.java
│   │   ├── model/
│   │   │   ├── LockRequest.java
│   │   │   └── LockResult.java
│   │   └── token/
│   │       ├── TokenGenerator.java          # interface
│   │       └── UuidTokenGenerator.java
│   ├── main/resources/
│   │   ├── application.yml
│   │   ├── META-INF/spring/
│   │   │   └── ...AutoConfiguration.imports
│   │   └── scripts/
│   │       ├── acquire.lua
│   │       ├── release.lua
│   │       └── renew.lua
│   └── test/java/com/vamva/distributedlock/
│       ├── backend/
│       │   └── InMemoryLockBackendTest.java
│       ├── engine/
│       │   └── LockEngineTest.java
│       ├── token/
│       │   └── TokenGeneratorTest.java
│       └── integration/
│           ├── DistributedLockIntegrationTest.java
│           ├── LuaScriptContractTest.java
│           └── ChaosTest.java
├── docs/
│   ├── architecture.md
│   ├── safety.md
│   ├── performance.md
│   ├── redis-deployment.md
│   ├── grafana.md
│   └── adr/
│       ├── ADR-001-use-redis-backend.md
│       ├── ADR-002-lease-based-locking.md
│       ├── ADR-003-owner-token-required.md
│       ├── ADR-004-lua-scripts-for-atomicity.md
│       ├── ADR-005-fail-closed-default.md
│       └── ADR-006-non-reentrant-semantics.md
├── .github/workflows/ci.yml
├── docker-compose.yml
├── build.gradle.kts
├── settings.gradle.kts
├── CHANGELOG.md
├── LICENSE
└── README.md
```

## Design Principles

| Principle | How It's Applied |
|---|---|
| **Lease-based locking** | Locks expire automatically via TTL, preventing orphaned locks |
| **Ownership safety** | Only the token holder can renew or release a lock |
| **Atomic backend operations** | Lua scripts guarantee atomicity for release and renew |
| **Fail-safe behavior** | Backend unavailable = acquisition fails (fail-closed default) |
| **Observability-first** | Every operation is metered, logged, and traced |
| **Best-effort mutual exclusion** | Not a consensus system; designed for coordination, not correctness guarantees |

## Comparison with Existing Solutions

| Tool | Type | Approach |
|---|---|---|
| Redis `SET NX` locks | Manual | Simple but unsafe without ownership checks |
| Redisson | Full-featured library | Rich features, heavy dependency, reentrant by default |
| ZooKeeper | Consensus-based | Strong consistency via ZAB protocol, operational complexity |
| etcd | Consensus-based | Linearizable locks, requires etcd cluster |
| **This project** | **Embedded library** | **Lease-based, ownership-safe, observability-first, minimal dependencies** |

## Documentation

| Document | Description |
|---|---|
| [Architecture](docs/architecture.md) | Component overview, sequence diagrams, deployment topology |
| [Safety](docs/safety.md) | Consistency model, failure modes, misuse scenarios |
| [Performance](docs/performance.md) | Latency model, throughput, memory characteristics |
| [Redis Deployment](docs/redis-deployment.md) | Standalone, Sentinel, Cluster config, connection pooling |
| [Grafana](docs/grafana.md) | Dashboard template, panel descriptions, alerting |

### Architecture Decision Records

| ADR | Decision |
|---|---|
| [ADR-001](docs/adr/ADR-001-use-redis-backend.md) | Use Redis as lock backend |
| [ADR-002](docs/adr/ADR-002-lease-based-locking.md) | Use lease-based locking instead of permanent locks |
| [ADR-003](docs/adr/ADR-003-owner-token-required.md) | Require owner token for renew/release |
| [ADR-004](docs/adr/ADR-004-lua-scripts-for-atomicity.md) | Use Lua scripts for atomic release/renew |
| [ADR-005](docs/adr/ADR-005-fail-closed-default.md) | Default to fail-closed on backend failure |
| [ADR-006](docs/adr/ADR-006-non-reentrant-semantics.md) | Non-reentrant lock semantics |
| [ADR-007](docs/adr/ADR-007-fencing-tokens.md) | Fencing tokens for stale owner protection |
| [ADR-008](docs/adr/ADR-008-annotation-aop.md) | @DistributedLock annotation with AOP |

## Roadmap

### v0.1.0 (Current)
- Redis + in-memory backends with Resilience4j circuit breaker (configurable)
- tryAcquire, acquire with timeout, renew, release, executeWithLock
- Configurable retry policy (exponential backoff + jitter)
- Prometheus metrics + Micrometer Observation tracing spans
- Spring Boot Starter packaging with auto-configuration
- Health indicator for backend status
- Input validation on all API operations and configuration
- TokenGenerator interface for pluggable token strategies
- Lua scripts with key-not-found vs token-mismatch distinction
- GitHub Actions CI pipeline with Redis service container
- Fencing tokens (monotonically increasing via Redis INCR)
- Auto-renew helper (LockHandle with background lease renewal)
- Graceful shutdown (LockRegistry @PreDestroy releases all locks)
- `@DistributedLock` annotation with SpEL and AOP
- Usage examples: batch-job, scheduler-singleton, leader-election
- 8 Architecture Decision Records
- Documentation: architecture, safety/threat model, performance, Redis deployment, Grafana
- Comprehensive test suite: unit, Lua contract, integration, chaos (Testcontainers)
- MIT License

### Future Considerations
- Admin inspection API / lock debugging endpoint
- Lock contention dashboard
- SQL backend
- Reentrant locks
