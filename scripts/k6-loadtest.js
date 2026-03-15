import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/**
 * k6 load test for Distributed Lock library.
 *
 * Prerequisites:
 *   docker compose up -d
 *   ./gradlew bootRun
 *
 * Usage:
 *   k6 run --vus 50 --duration 60s scripts/k6-loadtest.js
 *   k6 run --env SCENARIO=contention scripts/k6-loadtest.js
 *
 * This test exercises lock acquire/release via a simple REST endpoint.
 * You need to expose a test endpoint in your app, e.g.:
 *
 *   @PostMapping("/api/test/lock/{key}")
 *   public ResponseEntity<?> testLock(@PathVariable String key) {
 *       LockResult result = lockClient.tryAcquire(LockRequest.builder()
 *           .resourceKey("loadtest:" + key).leaseMs(5000).build());
 *       if (!result.isAcquired()) return ResponseEntity.status(409).build();
 *       lockClient.release(result.getResourceKey(), result.getLockToken());
 *       return ResponseEntity.ok().build();
 *   }
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'independent';

const acquireSuccess = new Counter('lock_acquire_success');
const acquireFailed = new Counter('lock_acquire_failed');
const acquireLatency = new Trend('lock_acquire_latency_ms');

export const options = {
  scenarios: {
    independent: {
      executor: 'constant-vus',
      vus: 50,
      duration: '60s',
      exec: 'independentKeys',
      tags: { scenario: 'independent' },
    },
    contention: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'singleKey',
      startTime: '65s',
      tags: { scenario: 'contention' },
    },
  },
  thresholds: {
    'lock_acquire_latency_ms{scenario:independent}': ['p(95)<10'],
    'lock_acquire_latency_ms{scenario:contention}': ['p(95)<50'],
  },
};

// Scenario 1: Each VU uses a unique lock key — no contention
export function independentKeys() {
  const key = `vu-${__VU}-${__ITER}`;
  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/test/lock/${key}`);
  const latency = Date.now() - start;

  acquireLatency.add(latency, { scenario: 'independent' });

  if (check(res, { 'acquired': (r) => r.status === 200 })) {
    acquireSuccess.add(1);
  } else {
    acquireFailed.add(1);
  }

  sleep(0.01);
}

// Scenario 2: All VUs compete for the same lock key — high contention
export function singleKey() {
  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/test/lock/shared-resource`);
  const latency = Date.now() - start;

  acquireLatency.add(latency, { scenario: 'contention' });

  if (res.status === 200) {
    acquireSuccess.add(1);
  } else {
    acquireFailed.add(1);
  }

  sleep(0.05);
}
