from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")

    url: str  # 필수값 — 기본값 없음. 없으면 인스턴스화 시 ValidationError로 즉시 실패
