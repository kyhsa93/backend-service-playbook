from pydantic_settings import BaseSettings, SettingsConfigDict


class JwtConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_")

    # No default — outside production, validate_env() fails fast if it's missing.
    # In production, Secrets Manager (app/jwt) separately fills this in at lifespan
    # startup, so this value can be empty (see main.py, secret-manager.md).
    secret: str | None = None
