// Same "sane dev default, real value required in prod" shape as jwt.config.ts's JWT_SECRET /
// aws.config.ts's getAwsEndpoint — with no OTEL_EXPORTER_OTLP_ENDPOINT set (local dev/test),
// @/tracing exports spans to the console instead, so npm test/npm run start never need a real
// OpenTelemetry collector running. Set it to a real OTLP/HTTP collector endpoint (Jaeger,
// Tempo, a Datadog agent, etc.) in staging/production.
export function getOtlpEndpoint(): string | undefined {
  return process.env.OTEL_EXPORTER_OTLP_ENDPOINT
}

export function getServiceName(): string {
  return process.env.OTEL_SERVICE_NAME ?? 'account-service'
}
