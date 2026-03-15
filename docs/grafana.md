# Grafana Dashboard

## Panels

| Panel | Type | Description |
|---|---|---|
| **Acquire Rate** | Time series | Successful vs failed acquisitions per second |
| **Release Rate** | Time series | Successful vs failed releases per second |
| **Renew Rate** | Time series | Successful vs failed renewals per second |
| **Acquire Latency** | Time series | p50/p95/p99 of lock acquisition time |
| **Contention Wait** | Time series | p50/p95/p99 of time spent waiting in acquire-with-timeout |
| **Backend Errors** | Stat | Redis error count over 5 minutes |
| **Acquire Success Ratio** | Gauge | Success / (Success + Failed) over 5 minutes |
| **Fencing Tokens** | Stat | Latest fencing token per resource key (contention indicator) |

### Import

1. Open Grafana → Dashboards → Import
2. Upload `grafana-dashboard.json` from the project root
3. Select your Prometheus data source
4. Click Import

## Dashboard Layout

```
┌──────────────────────────┬──────────────────────────┐
│   Acquire Rate (ops/s)   │  Release Rate (ops/s)    │
│   [success vs failed]    │  [success vs failed]     │
├──────────────────────────┼─────────────┬────────────┤
│  Acquire Latency         │  Backend    │  Acquire   │
│  [p50/p95/p99]           │  Errors     │  Success   │
│                          │  [stat]     │  Ratio     │
├──────────────────────────┼─────────────┼────────────┤
│  Contention Wait         │  Renew Rate │ Operations │
│  [p50/p95/p99]           │  [success/  │  by Backend│
│                          │   failed]   │  [pie]     │
└──────────────────────────┴─────────────┴────────────┘
```

## PromQL Examples

### Acquire Success Rate
```promql
rate(distributed_lock_acquire_success_total[5m])
```

### Acquire Failure Rate
```promql
rate(distributed_lock_acquire_failed_total[5m])
```

### Acquire Latency (p95)
```promql
histogram_quantile(0.95, rate(distributed_lock_acquire_duration_ms_bucket[5m]))
```

### Backend Error Rate
```promql
rate(distributed_lock_backend_errors_total[1m])
```

### Contention Wait (p99)
```promql
histogram_quantile(0.99, rate(distributed_lock_contention_wait_ms_bucket[5m]))
```

### Latest Fencing Token
```promql
distributed_lock_fencing_token_latest
```

High fencing token values with frequent increments indicate contention on a resource key.

## Alerting Recommendations

| Alert | Condition | Severity |
|---|---|---|
| High acquisition failures | `rate(distributed_lock_acquire_failed_total[5m]) > 10` | Warning |
| Backend errors | `rate(distributed_lock_backend_errors_total[1m]) > 0` | Critical |
| High acquire latency | `histogram_quantile(0.99, ...) > 0.01` (10ms) | Warning |
| Sustained contention | `rate(distributed_lock_contention_wait_ms_count[5m]) > 50` | Info |
