# Environment Configuration Management

> Framework-agnostic principles: [../../../../docs/architecture/config.md](../../../../docs/architecture/config.md)

## Current implementation — `DatabaseConfig`/`JwtConfig`/`AwsConfig` are all actual code

`DatabaseConfig` (`pydantic_settings.BaseSettings`) in `src/config/database_config.py` validates `DATABASE_URL` as required, and `validate_env()` in `src/config/validator.py` calls it at `main.py`'s module-import time, immediately exiting via `sys.exit(1)` if it's missing — the "Fail-Fast" section below shows this actual code as-is.

`JwtConfig` in `src/config/jwt_config.py` is validated the same way. However, `JWT_SECRET` is an **exception only in production** — in production, `main.py`'s `lifespan` fetches it from Secrets Manager (`app/jwt`) and fills it in (see [secret-manager.md](secret-manager.md)), so an empty environment variable isn't subject to fail-fast there. `validate_env()` only blocks a missing `JwtConfig.secret` via fail-fast when `APP_ENV != "production"`.

**AWS credentials** (`AWS_REGION`/`AWS_ENDPOINT_URL`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`) are also encapsulated in `AwsConfig` in `src/config/aws_config.py`. Both `src/common/aws_secret_service.py` and `src/account/infrastructure/notification/notification_service.py` construct a boto3 client via `AwsConfig().client_kwargs()` — access to AWS credentials is centralized through a single `AwsConfig`, rather than each calling `os.getenv(..., "test")` separately. However, since `AwsConfig` has local-development defaults for every field (`region="us-east-1"`, `access_key_id="test"`, etc.), it is **not subject to fail-fast** — AWS credentials are typically replaced by an IAM role in production, so unlike DB connection info/the JWT secret, they aren't treated as "a value that must terminate the process if missing" (the other language implementations likewise exclude the SES/AWS credentials themselves from fail-fast).

Below is the fail-fast pattern using Pydantic `BaseSettings`. `DatabaseConfig`/`JwtConfig`/`AwsConfig` are all actual code.

---

## Pydantic `BaseSettings` — configuration classes split by concern

```
pydantic-settings
```

Configuration classes are split by concern. Every value is never packed into one giant configuration class.

```python
# src/config/database_config.py — actual code
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DATABASE_")

    url: str   # required — no default. Instantiating without it immediately fails with a ValidationError
```

```python
# src/config/jwt_config.py — actual code
from pydantic_settings import BaseSettings, SettingsConfigDict


class JwtConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_")

    # No default — but since Secrets Manager fills it in instead in production,
    # the type itself is Optional, and validate_env() decides "is it required" (see below).
    secret: str | None = None
```

```python
# src/config/aws_config.py — actual code
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
```

`client_kwargs()` is shaped so it can be spread as-is with `**` into `aioboto3.Session().client(...)` — both `src/common/aws_secret_service.py` and `src/account/infrastructure/notification/notification_service.py` build their boto3 client via this method.

Currently, `src/config/` has `database_config.py`, `jwt_config.py`, `aws_config.py`, and `validator.py`.

**Default-value principles:**
- A value that works as-is in local development (`region`) gets a default.
- An item that must never be empty in production (`DatabaseConfig.url`) is **never given a default in its type** — Pydantic throws a `ValidationError` if it's missing.
- `JwtConfig.secret` is an exception: its type is `str | None`, but since "whether it's required" depends on the environment (`APP_ENV`), this is enforced not through the Pydantic type system but through `validate_env()`'s explicit branching — since Secrets Manager fills the value in production, the type itself can't be made required.

---

## Fail-Fast — validating environment variables at startup

If a required setting is missing, the process is immediately terminated at the app-startup stage. Failing fast at the startup stage is far safer than an outage occurring at runtime due to a misconfiguration.

```python
# src/config/validator.py — actual code
import os
import sys

from pydantic import ValidationError

from .database_config import DatabaseConfig
from .jwt_config import JwtConfig


def validate_env() -> None:
    try:
        DatabaseConfig()   # type: ignore[call-arg]  — the value is filled from an environment variable
        jwt_config = JwtConfig()   # type: ignore[call-arg]
    except ValidationError as exc:
        print(f"Environment variable validation failed:\n{exc}", file=sys.stderr)
        sys.exit(1)   # terminate immediately

    # In production, Secrets Manager (app/jwt) fills the secret at lifespan startup,
    # so the JWT_SECRET environment variable can be absent — in every other environment it's explicitly required.
    if os.getenv("APP_ENV") != "production" and not jwt_config.secret:
        print("Environment variable validation failed:\nJWT_SECRET is not set.", file=sys.stderr)
        sys.exit(1)
```

**Why `JwtConfig.secret` isn't required in production**: a Pydantic field type alone can't express "whether it's required depends on the environment" — so it's left as `secret: str | None = None`, and `validate_env()` checks `APP_ENV` directly, only failing fast when it isn't production.

It's called at module-import time, before `main.py` enters `lifespan`, so the app fails before it can receive any request.

```python
# main.py
from src.config.validator import validate_env

validate_env()   # on failure, the process exits right here — no code after this runs

app = FastAPI(title="Account Service", lifespan=lifespan)
```

**Why `sys.exit(1)` is used:** if the app starts up with a bad configuration, it fails in unexpected ways while handling requests (e.g. if `SES_SENDER_EMAIL` is an empty string, SES only rejects it at send time). Fail-fast is detected immediately by the deployment pipeline (health check, readiness), letting operators notice the problem quickly.

---

## Sensitive values — environment variables vs. Secrets Manager

| Item | Recommended approach |
|------|----------|
| General settings (hostname, port, timeout, region) | Environment variables |
| Sensitive values (the password in `DATABASE_URL`, `JWT_SECRET`, AWS credentials) | Secrets Manager |

Environment variables can be exposed in container logs, the process list (`/proc/<pid>/environ`), or the orchestrator UI. A DB password, JWT secret, etc. should be injected at app startup using **AWS Secrets Manager**. In local development, environment variables/`.env` are used as-is, branching to a Secrets Manager lookup in production.

→ See [secret-manager.md](secret-manager.md) for lookup/caching implementation details.

Never hardcode a secret in code or commit a `.env` file. `.env` is for local development only and is included in `.gitignore` (see `implementations/fastapi/examples/.gitignore`).

---

## Configuration access pattern — only in the Infrastructure layer

Configuration values are referenced directly only in the Infrastructure layer (`infrastructure/`, `database.py`, `main.py`). `application/` and `domain/` never import `os.environ` or a configuration object directly.

```python
# correct approach — accessing configuration in the Infrastructure layer
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session   # session is assembled from configuration by Infrastructure (database.py)

# incorrect approach — an Application Handler references an environment variable directly
class DepositHandler:
    async def execute(self, cmd: DepositCommand) -> Transaction:
        url = os.environ["DATABASE_URL"]   # forbidden — a Handler must not know about configuration
```

`src/account/infrastructure/notification/notification_service.py` and `src/common/aws_secret_service.py` each instantiate `AwsConfig()` and build their boto3 client via `client_kwargs()` — correctly placed (Infrastructure), and assembled through `AwsConfig` rather than calling `os.getenv` scattered around.

Not every `config/` file needs to be a `BaseSettings` class — `config/llm_config.py` (`OLLAMA_BASE_URL`, `REFUND_CLASSIFIER_MODEL`) and `config/fraud_risk_config.py` (`FRAUD_SCORER_MODE`, `FRAUD_SCORER_BASE_URL`) are plain `os.getenv(...)`-with-a-default functions instead, since these values (which mode/host to talk to) are simple opt-in toggles with a safe default, not values that should halt startup if missing. `payment/infrastructure/refund_fraud_risk_scorer_native_impl.py` (in-process) and `payment/infrastructure/refund_fraud_risk_scorer_http_impl.py` (the shared `services/fraud-risk-scorer` microservice) are two implementations of `RefundFraudRiskScorer` selected via `get_fraud_scorer_mode()` — see `docs/architecture/domain-service.md` (root, shared across languages) for the pattern this demonstrates.

Whether `domain/`/`application/` call `os.environ`/`os.getenv` directly is checked by the harness's `no-direct-env-access-outside-config` rule.

---

## Principles

- **Fail-fast**: required environment variables are validated at the moment a `pydantic_settings.BaseSettings` is instantiated, terminating immediately via `sys.exit(1)` on failure.
- **Separate by concern**: split configuration classes like `config/database_config.py`, `config/jwt_config.py`, `config/aws_config.py`.
- **Sensitive values go through Secrets Manager**: passwords, API keys, and the JWT secret are managed via Secrets Manager rather than environment variables in production.
- **Configuration access is confined to the Infrastructure layer**: `application/` and `domain/` never depend on configuration directly.
- **`.env` is local-only**: included in `.gitignore`, never committed.

---

### Related documents

- [container.md](container.md) — how environment variables are injected (at container run time)
- [graceful-shutdown.md](graceful-shutdown.md) — startup order (the relationship between `lifespan` and `validate_env()`)
- [secret-manager.md](secret-manager.md) — Secrets Manager lookup/caching details
- [local-dev.md](local-dev.md) — local development environment setup (`.env.development`)
