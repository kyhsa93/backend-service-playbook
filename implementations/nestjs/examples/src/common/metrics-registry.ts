import { collectDefaultMetrics, Counter, Histogram, Registry } from 'prom-client'

// A single process-wide Registry, constructed explicitly here rather than reaching for
// prom-client's implicit global `register` — the same "build one explicit shared object rather
// than a hidden singleton" reasoning as outbox/sqs-client-provider.ts's SQS_CLIENT. Both
// MetricsInterceptor (records every HTTP request) and MetricsController (serves GET /metrics)
// import this same instance.
export const metricsRegistry = new Registry()

// Default Node.js process metrics (event-loop lag, heap/RSS, GC pauses, active handles, etc.) —
// the "plus default Node.js process metrics" half of the deliverable, alongside the two
// HTTP-specific metrics below.
collectDefaultMetrics({ register: metricsRegistry })

export const httpRequestsTotal = new Counter({
  name: 'http_requests_total',
  help: 'Total number of HTTP requests',
  labelNames: ['method', 'route', 'status_code'],
  registers: [metricsRegistry]
})

export const httpRequestDurationSeconds = new Histogram({
  name: 'http_request_duration_seconds',
  help: 'HTTP request duration in seconds',
  labelNames: ['method', 'route', 'status_code'],
  registers: [metricsRegistry]
})
