import { AsyncLocalStorage } from 'async_hooks'

interface Store {
  correlationId: string
  // Absent when there's no active OpenTelemetry span (e.g. tracing is disabled, or the current
  // async scope was never entered through a traced request). See correlation-id.middleware.ts,
  // which is the only place this is populated for the HTTP request path, and
  // outbox/outbox-consumer.ts, which populates it for the async event-processing path — both
  // feed the same field so every structured log (logging.interceptor.ts) can include trace_id
  // without a parallel AsyncLocalStorage instance.
  traceId?: string
}

const storage = new AsyncLocalStorage<Store>()

export const CorrelationIdStore = {
  run: <T>(store: Store, fn: () => T): T => storage.run(store, fn),
  getId: () => storage.getStore()?.correlationId,
  getTraceId: () => storage.getStore()?.traceId
}
