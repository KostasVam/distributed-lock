# ADR-008: @DistributedLock Annotation with AOP

**Status:** Accepted
**Date:** 2026-03-16

## Context

The programmatic API (`lockClient.tryAcquire(...)`, `lockClient.executeWithLock(...)`) requires explicit lock management code in every method that needs locking. For simple use cases (e.g., "this method should only run on one instance"), declarative annotation-based locking improves developer experience.

## Decision

Provide a `@DistributedLock` annotation that can be placed on Spring bean methods. An AOP aspect (`DistributedLockAspect`) intercepts annotated methods, acquires the lock before execution, and releases it in a finally block.

The annotation supports SpEL expressions for dynamic key resolution.

## Usage

```java
@DistributedLock(key = "'job:daily-settlement'", leaseMs = 60000)
public void runSettlement() { ... }

@DistributedLock(key = "'invoice:' + #id", waitTimeoutMs = 5000)
public void processInvoice(long id) { ... }
```

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **Programmatic API only** | Full control, explicit | Boilerplate in every method |
| **Template pattern** | Reusable, no AOP | Still requires wrapping code |
| **Annotation + AOP** (chosen) | Declarative, clean methods | Requires Spring AOP, proxy limitations |

## Consequences

### Positive

- Zero-boilerplate locking for simple use cases
- SpEL expressions allow dynamic keys based on method parameters
- Coexists with programmatic API — use whichever fits

### Negative

- Spring AOP proxy limitations: annotation doesn't work on private methods or self-invocations
- Adds spring-boot-starter-aop dependency
- Less visible than explicit lock management (could surprise developers unfamiliar with AOP)

### Operational Guidance

- Use `@DistributedLock` for simple, stateless methods
- Use programmatic API (`executeWithLock`, `acquireWithAutoRenew`) for complex flows
- Be aware of Spring AOP proxy limitations (annotated methods must be called through the proxy)
