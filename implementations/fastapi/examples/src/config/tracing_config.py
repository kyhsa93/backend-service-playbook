from pydantic_settings import BaseSettings, SettingsConfigDict


class TracingConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OTEL_")

    # No default, and intentionally optional — unlike DATABASE_URL/JWT_SECRET, tracing must
    # not fail validate_env() when unset. Local dev/CI/tests run with no collector at all
    # (observability.md, "Metrics/tracing"): spans are still created (trace_id/traceparent
    # keep working end-to-end) but exported nowhere. Set OTEL_EXPORTER_OTLP_ENDPOINT to a
    # real collector's OTLP/HTTP endpoint (e.g. http://otel-collector:4318) to actually ship
    # spans in staging/production — the same sane-dev-default / real-value-required-in-prod
    # split used by JWT_SECRET/AwsConfig (config.md).
    exporter_otlp_endpoint: str | None = None
    service_name: str = "account-service"
