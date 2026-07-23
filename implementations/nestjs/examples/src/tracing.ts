// OpenTelemetry bootstrap — must be the very first thing main.ts imports, before @nestjs/core
// or anything that transitively pulls in express/http. @opentelemetry/instrumentation-http
// patches Node's http module in place, and only requests made after NodeSDK#start() runs get
// instrumented, so this file's top-level sdk.start() has to execute before Nest (and the
// express server it creates under the hood) is ever required.
//
// Mirrors config's "sane dev default, real value required in prod" shape (see
// config/tracing.config.ts, and e.g. jwt.config.ts's JWT_SECRET / aws.config.ts's
// getAwsEndpoint): with no OTEL_EXPORTER_OTLP_ENDPOINT set (local dev/test), spans are printed
// to the console, so nothing here ever requires a real collector to be running for the app —
// or the test suite — to work. Setting OTEL_EXPORTER_OTLP_ENDPOINT switches to a real OTLP/HTTP
// exporter in staging/production.
//
// NodeSDK also registers a W3C TraceContext propagator as the process-wide default, which
// src/outbox/trace-context.ts reuses to carry the trace across the async SQS hop
// (docs/architecture/observability.md — "including traceparent in the outbox payload").
import { NodeSDK } from '@opentelemetry/sdk-node'
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { ConsoleSpanExporter } from '@opentelemetry/sdk-trace-base'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions'

import { getOtlpEndpoint, getServiceName } from '@/config/tracing.config'

const otlpEndpoint = getOtlpEndpoint()

const sdk = new NodeSDK({
  resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: getServiceName() }),
  traceExporter: otlpEndpoint ? new OTLPTraceExporter({ url: otlpEndpoint }) : new ConsoleSpanExporter(),
  instrumentations: [new HttpInstrumentation()]
})

sdk.start()

// shutdownTracing() tears down the TracerProvider (span processor, exporter, etc.) that
// sdk.start() set up above. Exported so a test that imports this module for real (as opposed
// to every other *.e2e-spec.ts, which never initializes OpenTelemetry at all — see
// observability.e2e-spec.ts) can call it in its own afterAll and let the process exit
// naturally; without an explicit shutdown, the live SpanProcessor/exporter this starts has no
// other trigger to tear itself down before the normal SIGTERM/SIGINT path below, which a Jest
// run never sends.
export function shutdownTracing(): Promise<void> {
  return sdk.shutdown()
}

// Flushes any buffered spans before the process exits. Registered directly on `process` rather
// than as a Nest OnApplicationShutdown provider — this module runs (and the SDK starts)
// before Nest's DI container even exists, so it can't participate in
// app.enableShutdownHooks() (docs/architecture/graceful-shutdown.md). It runs alongside that
// sequence, not as a replacement for it.
process.on('SIGTERM', () => { void sdk.shutdown() })
process.on('SIGINT', () => { void sdk.shutdown() })
