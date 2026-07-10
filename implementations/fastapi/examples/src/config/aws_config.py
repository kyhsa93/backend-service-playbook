from pydantic_settings import BaseSettings, SettingsConfigDict


class AwsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AWS_")

    region: str = "us-east-1"
    endpoint_url: str | None = None  # LocalStack용 — 미설정 시 실제 클라우드 사용
    access_key_id: str = "test"  # 로컬 전용 기본값 — 운영은 IAM 역할/Secrets Manager로 대체
    secret_access_key: str = "test"

    def client_kwargs(self) -> dict[str, str | None]:
        return {
            "region_name": self.region,
            "endpoint_url": self.endpoint_url,
            "aws_access_key_id": self.access_key_id,
            "aws_secret_access_key": self.secret_access_key,
        }
