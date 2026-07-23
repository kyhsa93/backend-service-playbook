package config

import "os"

// OTLPEndpoint returns the OTLP/HTTP collector endpoint (e.g.
// "otel-collector:4318") that internal/infrastructure/tracing.NewTracerProvider
// exports spans to, read from OTEL_EXPORTER_OTLP_ENDPOINT (the standard
// OpenTelemetry SDK env var name — reused as-is rather than inventing a
// repo-specific one, so this app behaves like any other OTel-instrumented
// service for whoever operates it).
//
// Same "sane dev default, real value required in prod" shape as
// OllamaBaseURL/FraudScorerBaseURL (llm.go/fraud_risk.go): empty means "no
// collector configured," and NewTracerProvider falls back to a stdout
// exporter so local development never needs a real collector running.
func OTLPEndpoint() string {
	return os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
}
