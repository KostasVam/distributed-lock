# ADR-004: Use Lua Scripts for Atomic Release/Renew

**Status:** Accepted
**Date:** 2026-03-15

## Context

Release and renew operations require two steps: (1) verify the caller owns the lock, (2) delete or extend the key. If these are separate Redis commands, a race condition exists: another client could acquire the lock between the GET and DEL.

```
Client A: GET lock:x → "token-A" (matches)
                                    Client B: SET lock:x "token-B" NX → OK (lock expired between GET and DEL)
Client A: DEL lock:x               → Deletes Client B's lock!
```

## Decision

Use Redis Lua scripts for release and renew. Lua scripts execute atomically within Redis — no other command can interleave.

## Alternatives Considered

| Alternative | Pros | Cons |
|---|---|---|
| **Separate GET + DEL** | No Lua needed | Race condition between commands |
| **Redis transactions (MULTI/EXEC)** | Atomic execution | WATCH-based optimistic locking adds complexity; retries needed |
| **Lua scripts** | True atomic execution | Slightly more complex to write and debug |

## Consequences

### Positive

- Zero race conditions: ownership check and mutation are indivisible
- Single round trip to Redis
- Scripts are cached by Redis after first execution (SHA-based caching)

### Negative

- Lua scripts are harder to debug than simple Redis commands
- Scripts must be loaded from classpath resources

### Implementation

Three scripts in `src/main/resources/scripts/`:
- `acquire.lua`: `SET key value NX PX leaseMs`
- `release.lua`: `if GET(key) == token then DEL(key)`
- `renew.lua`: `if GET(key) == token then PEXPIRE(key, leaseMs)`
