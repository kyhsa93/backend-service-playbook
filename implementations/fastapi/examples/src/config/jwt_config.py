from pydantic_settings import BaseSettings, SettingsConfigDict


class JwtConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_")

    # 기본값 없음 — 프로덕션 외 환경에서는 validate_env()가 누락 시 fail-fast한다.
    # 프로덕션에서는 lifespan 기동 시 Secrets Manager(app/jwt)가 별도로 채우므로
    # 이 값이 비어 있어도 된다(main.py, secret-manager.md 참고).
    secret: str | None = None
