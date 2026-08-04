// Every other *.e2e-spec.ts boots the real AppModule via test/support/test-app.ts, but never
// initializes OpenTelemetry — that's main.ts's very first import, before Nest itself. This spec
// is specifically about trace propagation, so it needs the real thing: importing '@/tracing'
// here runs the exact same NodeSDK#start() main.ts runs, registering the W3C propagator +
// AsyncLocalStorage-based context manager for this process before anything else executes.
//
// One thing this can't verify under Jest: @opentelemetry/instrumentation-http's automatic
// HTTP-request span creation. It relies on monkey-patching Node's http module via
// require-in-the-middle, which Jest's own sandboxed module registry does not propagate through
// — confirmed independently with a standalone (non-Jest) repro script that a plain http.Server
// handling a plain http.get *does* see an active span once NodeSDK#start() has run, so this is
// a Jest tooling limitation, not a bug in this app. What this spec verifies instead is the part
// this repo's own code is actually responsible for: given an active span (however it got
// there — an HTTP request in production, manually started here), OutboxWriter captures it onto
// the outbox row (src/outbox/trace-context.ts), OutboxPoller forwards it as a real SQS message
// attribute, and OutboxConsumer re-hydrates it before invoking a Handler — a real round trip
// through LocalStack SQS, not a mock.
//
// Because this is the only spec that actually starts the real NodeSDK, it's also the only one
// that must explicitly tear it down: the live TracerProvider/exporter sdk.start() sets up has
// no other trigger to shut down before a Jest run ends (there's no SIGTERM here, and Jest
// itself never calls shutdownTracing() for you), so afterAll calls it explicitly — otherwise
// the process never becomes idle and Jest hangs after printing its results instead of exiting.
import { shutdownTracing } from '@/tracing'

import { trace } from '@opentelemetry/api'
import { DataSource } from 'typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { TransactionManager } from '@/database/transaction-manager'
import { StartedTestApp, startTestApp } from './support/test-app'

// A W3C traceparent header: "00-<32 hex trace id>-<16 hex span id>-<2 hex flags>".
const TRACEPARENT_PATTERN = /^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/
const EVENT_TYPE = 'ObservabilityTraceparentTest'

describe('traceparent propagation across the Outbox hop (e2e)', () => {
  let testApp: StartedTestApp
  let dataSource: DataSource
  let outboxWriter: OutboxWriter
  let transactionManager: TransactionManager

  // Populated by the test Handler registered below, once OutboxConsumer has re-hydrated the
  // originating trace and invoked it.
  const observedTraceIds: string[] = []

  beforeAll(async () => {
    testApp = await startTestApp()
    dataSource = testApp.dataSource
    outboxWriter = testApp.app.get(OutboxWriter)
    transactionManager = testApp.app.get(TransactionManager)

    testApp.app.get(EventHandlerRegistry).register(EVENT_TYPE, async () => {
      observedTraceIds.push(trace.getActiveSpan()?.spanContext().traceId ?? '')
    })
  }, 180000)

  afterAll(async () => {
    // testApp.stop() closes the app first (running OutboxModule's OnModuleDestroy hooks, which
    // is what actually stops OutboxConsumer's background poll loop) and only then stops the
    // containers — the reverse order leaves that loop retrying against a no-longer-running
    // Postgres/LocalStack forever and hangs Jest indefinitely.
    await testApp?.stop()
    // Tears down the TracerProvider this spec's `import '@/tracing'` started — without this,
    // the live span processor/exporter has nothing else to stop it before the test process
    // needs to exit, and Jest hangs indefinitely after printing its results instead of
    // returning control (see the top-of-file comment).
    await shutdownTracing()
  })

  async function waitForProcessedOutboxRow(): Promise<OutboxEntity | null> {
    for (let i = 0; i < 150; i++) {
      const row = await dataSource.getRepository(OutboxEntity).findOne({ where: { eventType: EVENT_TYPE } })
      if (row?.processed) return row
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return dataSource.getRepository(OutboxEntity).findOne({ where: { eventType: EVENT_TYPE } })
  }

  async function waitForObservedTraceId(): Promise<string | undefined> {
    for (let i = 0; i < 150 && observedTraceIds.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return observedTraceIds[0]
  }

  it('the trace_id OutboxConsumer re-hydrates for its Handlers matches the traceparent OutboxWriter captured when the row was written', async () => {
    const tracer = trace.getTracer('observability.e2e-spec')

    // Simulates "the span an inbound HTTP request would have had active" — the actual
    // capture/forward/re-hydrate mechanism under test (trace-context.ts, outbox-poller.ts,
    // outbox-consumer.ts) doesn't care whether the active span came from an HTTP request or
    // here; it only reads context.active().
    const writeTimeTraceId = await tracer.startActiveSpan(EVENT_TYPE, async (span) => {
      const traceId = span.spanContext().traceId
      await transactionManager.run(() => outboxWriter.saveAll([{ eventName: EVENT_TYPE }]))
      span.end()
      return traceId
    })

    const outboxRow = await waitForProcessedOutboxRow()
    expect(outboxRow?.traceParent).toMatch(TRACEPARENT_PATTERN)
    expect(TRACEPARENT_PATTERN.exec(outboxRow!.traceParent!)![1]).toBe(writeTimeTraceId)

    const consumeTimeTraceId = await waitForObservedTraceId()
    expect(consumeTimeTraceId).toBe(writeTimeTraceId)
  })
})
