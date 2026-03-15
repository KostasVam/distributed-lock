# Redis Deployment Guide

## Standalone (Development)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

```bash
docker compose up -d
```

## Redis Sentinel (High Availability)

Redis Sentinel provides automatic failover. When the master goes down, Sentinel promotes a replica.

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel1:26379
          - sentinel2:26379
          - sentinel3:26379
      password: your-redis-password
```

**Note:** During failover, lock acquisitions may fail for a brief period. The circuit breaker will handle this gracefully — acquisitions fail (fail-closed) until the new master is elected.

## Redis Cluster

Redis Cluster distributes keys across multiple nodes using hash slots.

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-node1:6379
          - redis-node2:6379
          - redis-node3:6379
        max-redirects: 3
```

### Key Distribution

Lock keys follow the pattern `lock:{resource_key}`. Since each lock operation touches only a single key, there are no `CROSSSLOT` issues. Each lock key lands on whichever hash slot Redis assigns based on the full key.

## Connection Pooling (Lettuce)

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 2000ms
```

### Recommended Settings by Scale

| Scale | max-active | max-idle | Notes |
|---|---|---|---|
| Low (< 100 locks/sec) | 8 | 4 | Default is fine |
| Medium (100-1k locks/sec) | 16 | 8 | Increase if seeing pool exhaustion |
| High (> 1k locks/sec) | 32 | 16 | Monitor `lettuce.pool.*` metrics |

## Timeout Configuration

```yaml
spring:
  data:
    redis:
      timeout: 2000ms
      connect-timeout: 3000ms
      lettuce:
        shutdown-timeout: 200ms
```

**Recommendation:** Keep timeouts low (1-3 seconds). The circuit breaker will trip after sustained failures, preventing timeout-induced latency from cascading.

## Monitoring

Key Redis metrics to watch:

| Metric | Command | Alert Threshold |
|---|---|---|
| Memory usage | `INFO memory` | > 80% maxmemory |
| Connected clients | `INFO clients` | > max-active pool |
| Ops/sec | `INFO stats` | Baseline + 50% |
| Key count | `DBSIZE` | Unexpected growth |

## Eviction Policy

Set Redis `maxmemory-policy` to `volatile-ttl` — this evicts keys with shortest TTL first, which aligns with lock lease expiration:

```
maxmemory 256mb
maxmemory-policy volatile-ttl
```
