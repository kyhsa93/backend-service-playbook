import { context, propagation } from '@opentelemetry/api'

// Extracts the current active span (if any) as a W3C traceparent header string (e.g.
// "00-<32 hex trace id>-<16 hex span id>-<2 hex flags>"), via the process-wide propagator
// src/tracing.ts registers at startup. OutboxWriter calls this when inserting a row, so the row
// carries the same trace as the HTTP request (or, for a row written while reacting to another
// event inside OutboxConsumer.handleMessage, the same trace as that earlier event) that
// produced it — docs/architecture/observability.md's "including traceparent in the outbox
// payload ... links the HTTP request → event processing into a single trace."
//
// Returns undefined when there's no active span (e.g. a Task Queue batch job with nothing to
// trace), so the column stays NULL rather than storing an empty string.
export function traceParentFromContext(): string | undefined {
  const carrier: Record<string, string> = {}
  propagation.inject(context.active(), carrier)
  return carrier.traceparent || undefined
}

// The inverse of traceParentFromContext — OutboxConsumer calls this after reading the
// "traceparent" SQS message attribute OutboxPoller forwarded, re-hydrating the originating span
// context before invoking the registered EventHandler, so any span/log it produces links back
// into the same trace. Returns the current (unchanged) active context when there's nothing to
// re-hydrate (e.g. a Domain Event whose row predates this feature, or one written by a process
// that had no active span).
export function contextWithTraceParent(traceParent: string | undefined): ReturnType<typeof context.active> {
  if (!traceParent) return context.active()
  return propagation.extract(context.active(), { traceparent: traceParent })
}
