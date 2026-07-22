# Secret Management

> Framework-agnostic principles: [../../../../docs/architecture/secret-manager.md](../../../../docs/architecture/secret-manager.md)

## `SecretService`/`AwsSecretService` — Secrets Manager is only wired up for the JWT secret

`src/common/secret_service.py` (ABC) and `src/common/aws_secret_service.py` (a Secrets Manager implementation with a TTL cache) exist as actual code. `main.py`'s `lifespan` calls `AwsSecretService().get_secret("app/jwt")` only when `APP_ENV == "production"`, fetching the JWT secret and injecting it via `set_jwt_secret()`.

**Cross-language difference — the gating variable name and polarity differ**: this repository gates on `APP_ENV == "production"` ("if production, use the cloud"). Go uses the same variable name `APP_ENV`, but with **reversed polarity** (`env != "production"`, "if not production, use local"). NestJS uses `NODE_ENV !== 'production'` (same polarity as Go, just a different variable name). Kotlin/java-springboot gate not on an environment variable but on the Spring **profile** (`Profiles.of("prod")`). Don't assume the name and polarity map directly when consulting another language's docs.

That said, **the DB connection info and the AWS credentials themselves still don't go through Secrets Manager** — both have local-development defaults, so neither is subject to fail-fast.

```python
# src/common/aws_secret_service.py — actual code. Encapsulates credentials via AwsConfig (see config.md)
async with self._boto_session.client(
    "secretsmanager", **AwsConfig().client_kwargs()
) as client:
```

The AWS credentials are encapsulated in `AwsConfig` in `src/config/aws_config.py` (see [config.md](config.md)). `region`/`access_key_id`/`secret_access_key` all have local-development defaults, so they aren't subject to fail-fast — this is because AWS credentials are typically replaced by an IAM role in production.

Also, the DB password (`DatabaseConfig.url` — see [config.md](config.md)) is still packed whole into a single environment variable, with no Secrets Manager lookup path — see the "Applying Secrets Manager to DB config" section below.

---

## Actual implementation — abstracting `SecretService` as a Technical Service

It follows the same spirit as the Technical Service pattern shown by `application/service/notification_service.py` (ABC in Application, implementation in Infrastructure), but its actual placement is not the per-domain `application/service/`/`infrastructure/` — it's **`src/common/`, shared by multiple domains** — because secret lookup is a purely infrastructural concern that doesn't belong to the Account domain (see [shared-modules.md](shared-modules.md)).

```python
# src/common/secret_service.py — actual code. Lives in common/, not application/service/
from abc import ABC, abstractmethod


class SecretService(ABC):
    @abstractmethod
    async def get_secret(self, secret_id: str) -> dict: ...
```

```python
# src/common/aws_secret_service.py — actual code. Lives in common/, not infrastructure/secret/
import json
import time

import aioboto3

from ..config.aws_config import AwsConfig


class AwsSecretService(SecretService):
    def __init__(self, ttl_seconds: int = 300) -> None:
        self._boto_session = aioboto3.Session()
        self._ttl_seconds = ttl_seconds
        self._cache: dict[str, tuple[dict, float]] = {}

    async def get_secret(self, secret_id: str) -> dict:
        cached = self._cache.get(secret_id)
        if cached is not None:
            value, expires_at = cached
            if expires_at > time.monotonic():
                return value

        async with self._boto_session.client(
            "secretsmanager", **AwsConfig().client_kwargs()   # local uses LocalStack
        ) as client:
            response = await client.get_secret_value(SecretId=secret_id)

        value = json.loads(response["SecretString"])
        self._cache[secret_id] = (value, time.monotonic() + self._ttl_seconds)
        return value
```

- **TTL cache**: looking up the same `secret_id` again within 5 minutes returns the cached value with no API call. Secrets Manager charges per call, so it isn't queried on every request.
- **Stored as JSON**: logically grouped values, like DB connection info, are stored as JSON in a single secret to reduce the number of API calls.
- **Endpoint branching**: LocalStack if `AWS_ENDPOINT_URL` is set, the real Secrets Manager otherwise — the same pattern already used by `notification_service.py`.

---

## Injection at startup — currently applied only to the JWT secret

Secrets are fetched **once at app startup**, not on every request. In the actual code, this pattern is applied to only one secret: the JWT secret.

```python
# main.py — actual code
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Secrets Manager is only called in production — elsewhere (local/test defaults),
    # only the environment variable (JWT_SECRET) is used, with no network call.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    yield
```

**This pattern is not applied to the DB configuration — it's still a plain environment variable.** The actual `DatabaseConfig` from [config.md](config.md) is as follows.

```python
# src/config/database_config.py — actual code
class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")
    url: str   # required — no Secrets Manager path, filled only from the environment variable (DATABASE_URL)
```

Below is a proposed extension for looking up DB connection info from Secrets Manager to build `DatabaseConfig` — **it has not been implemented yet.** If actually implemented, `url` should stay a required `str`, composed via a separate factory function like below (rather than contradicting the actual `DatabaseConfig.url: str` definition above by making `url` itself optional, the factory fills it in and passes it along).

```python
# (proposed, not yet implemented) — keeps DatabaseConfig.url required, but builds the
# value itself from Secrets Manager and passes it to the constructor
async def load_database_config(secret_service: SecretService) -> DatabaseConfig:
    if os.getenv("APP_ENV") == "production":
        secret = await secret_service.get_secret("app/database")
        return DatabaseConfig(
            url=f"postgresql+asyncpg://{secret['username']}:{secret['password']}@{secret['host']}:{secret['port']}/{secret['dbname']}"
        )
    return DatabaseConfig()   # local — loaded as-is from the environment variable (DATABASE_URL)
```

Performing this loading at the same point as `main.py`'s `validate_env()` (module-import time — see [graceful-shutdown.md](graceful-shutdown.md)) would surface a secret-lookup failure as a fail-fast at startup too.

---

## Local development — LocalStack

```bash
# localstack/init-secrets.sh — actual code. Creates app/jwt, not app/database
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

```yaml
# docker-compose.yml — actual code. secretsmanager is included in SERVICES
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager
```

→ See [local-dev.md](local-dev.md) for the full LocalStack setup.

---

## Principles

- **Never put sensitive values directly in environment variables**: in production, look them up from Secrets Manager. A hardcoded default like `"test"` is for local use only, and its absence in production must lead to fail-fast (silent failure is forbidden).
- **Apply a TTL cache**: Secrets Manager calls are billed and rate-limited.
- **Abstract via the `SecretService` ABC**: the same Technical Service structure as `notification_service.py`.
- **Store logically grouped values as JSON in a single secret**.
- **Local development uses LocalStack**: never accesses the real Secrets Manager.

### Related documents

- [config.md](config.md) — criteria for choosing environment variables vs. Secrets Manager, fail-fast validation
- [layer-architecture.md](layer-architecture.md) — the Technical Service pattern
- [local-dev.md](local-dev.md) — the LocalStack-based local development environment
