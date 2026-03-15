# Performance Characteristics

## Expected Latency

Each lock operation involves one synchronous Redis round trip. All operations use Lua scripts for atomicity.

### Per-Operation Latency

| Operation | Expected Latency | Notes |
|---|---|---|
| Acquire (SET NX PX) | < 2 ms | Single Redis command via Lua |
| Release (GET + DEL) | < 2 ms | Lua script, 2 commands |
| Renew (GET + PEXPIRE) | < 2 ms | Lua script, 2 commands |

### Acquire with Timeout

| Scenario | Latency |
|---|---|
| Lock free, first attempt | < 2 ms |
| Lock contended, released quickly | 50-200 ms (1-3 retries) |
| Lock contended, full timeout | Up to `waitTimeoutMs` |

## Throughput

### Bottleneck Analysis

The primary bottleneck is Redis round-trip time, not CPU.

| Component | Expected Throughput | Bottleneck? |
|---|---|---|
| Token generation (UUID) | Millions/sec | No |
| LockEngine logic | Millions/sec | No |
| Redis (single instance) | ~50-100K ops/sec | **Yes** |

### Scaling Factors

- **Independent lock keys**: Scale linearly with Redis throughput
- **Contention on single key**: Limited by retry backoff (thundering herd risk)
- **Multiple Redis instances**: Not applicable for single-key locks (but different keys can use different Redis instances)

### Contention Mitigation

High contention on a single lock key creates thundering herd effects. Mitigations:

- Jittered exponential backoff (enabled by default)
- Configurable initial and max backoff
- Short leases to reduce hold time

## Memory Model

### Redis Memory

Active keys at any point in time:

```
active_keys = number of currently held locks
```

Each key consumes approximately:
- Key: ~40-80 bytes (e.g., `lock:job:daily-settlement`)
- Value: ~36 bytes (UUID token)
- Redis overhead: ~80 bytes per key

**Example:** 1,000 active locks × ~200 bytes = **~200 KB**

Keys are automatically evicted by TTL (lease duration).

### JVM Memory

- Configuration: negligible (loaded once at startup)
- Metrics cache: O(metric_names) `Counter`/`Timer` instances
- In-memory backend: O(active_locks) entries with scheduled cleanup every 60s

## Retry Backoff Model

```
backoff(attempt) = exponentialMs/2 + random(0, exponentialMs/2)

where exponentialMs = min(initialBackoffMs * 2^attempt, maxBackoffMs)
```

Default configuration:
- `initialBackoffMs`: 50 ms
- `maxBackoffMs`: 2000 ms
- `jitter`: true

| Attempt | Base (ms) | With Jitter (ms) |
|---|---|---|
| 1 | 100 | 50-100 |
| 2 | 200 | 100-200 |
| 3 | 400 | 200-400 |
| 4 | 800 | 400-800 |
| 5 | 1600 | 800-1600 |
| 6+ | 2000 | 1000-2000 |
