from pydantic_settings import BaseSettings, SettingsConfigDict


class AwsConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AWS_")

    region: str = "us-east-1"
    endpoint_url: str | None = None  # for LocalStack — uses the real cloud if unset
    access_key_id: str = "test"  # local-only default — replaced by an IAM role/Secrets Manager in production
    secret_access_key: str = "test"

    def client_kwargs(self) -> dict[str, str | None]:
        return {
            "region_name": self.region,
            "endpoint_url": self.endpoint_url,
            "aws_access_key_id": self.access_key_id,
            "aws_secret_access_key": self.secret_access_key,
        }
