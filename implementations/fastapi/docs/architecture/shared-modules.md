# Shared Code Structure

## Current state — `common/`, `config/`, `auth/`, `outbox/` already exist as shared code outside any domain

Looking at this repository's `examples/src/` tree, the following live outside the `account/` package.

```
examples/src/
  __init__.py
  database.py            ← shared infrastructure: engine/session factory, the get_session() dependency. Assembled from DatabaseConfig().url
  common/                 ← shared infrastructure: pure utils/infra reused by multiple domains
    generate_id.py         ← generate_id() — generates IDs based on uuid.uuid4().hex (aggregate-id.md)
    logging_config.py      ← JsonFormatter, configure_logging() (observability.md)
    correlation.py         ← contextvars-based Correlation ID (cross-cutting-concerns.md)
    secret_service.py      ← SecretService ABC (secret-manager.md)
    aws_secret_service.py  ← AwsSecretService(SecretService) implementation (secret-manager.md)
  config/                  ← per-concern configuration classes (config.md)
    database_config.py     ← DatabaseConfig(BaseSettings) — validates the required DATABASE_URL value
    validator.py            ← validate_env() — a fail-fast entry point invoked at module import time
  auth/                    ← shared authentication (authentication.md) — follows the same 4-layer structure as account/
    domain/
      errors.py             ← InvalidTokenError
      token.py              ← TokenPayload
    application/
      service/
        auth_service.py     ← AuthService ABC (Technical Service interface)
    infrastructure/
      jwt_auth_service.py   ← JwtAuthService(AuthService), set_jwt_secret()
    interface/
      rest/
        auth_router.py      ← POST /auth/sign-in
        dependencies.py     ← get_current_user(), CurrentUser
        schemas.py
  outbox/                  ← shared infrastructure: the Outbox pattern (see domain-events.md)
    outbox_model.py       ← OutboxModel(Base) — reuses the same Base as account_repository.py
    outbox_writer.py       ← OutboxWriter — called in the same session by the Repository's save_<noun>()
    outbox_poller.py       ← OutboxPoller — publishes Outbox → SQS, started as a background task by main.py's lifespan
    outbox_consumer.py     ← OutboxConsumer — receives SQS → EventHandler, also a background task
    event_handlers.py      ← build_event_handlers() — assembles the eventType → handler dict (composition root)
  account/                ← 1st domain (Bounded Context)
    domain/
    application/
      event/               ← EventHandler — one per event_type, deserializes the Outbox payload and calls NotificationService
    infrastructure/
    interface/
  card/                   ← 2nd domain (Bounded Context) — communicates with account via Integration Events
    domain/ application/ infrastructure/ interface/
  payment/                ← 3rd domain (Bounded Context) — Payment/Refund
    domain/ application/ infrastructure/ interface/
    domain/refund_eligibility_service.py   ← a Domain Service that coordinates multiple Aggregates (Payment/Refund) — see domain-service.md
```

`outbox/` is a purely technical concern that doesn't belong to any domain (managing the Outbox table, draining events), so it lives in this top-level shared location rather than inside any domain package, and all three domains — Account/Card/Payment — share the same instance. `common/`, `config/`, and `auth/` are outside any domain for the same reason — they are purely technical/cross-cutting concerns unrelated to any specific domain's business rules.

```python
# src/database.py — actual code, shared infrastructure that doesn't belong to any domain
from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .config.database_config import DatabaseConfig

engine = create_async_engine(DatabaseConfig().url, echo=False)  # type: ignore[call-arg]
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        yield session
        await session.commit()
```

`database.py` can remain a single module file because all three domains — Account/Card/Payment — share the same `engine`/`SessionLocal` — there is no scenario yet where the transaction boundary needs to be split per domain as domains grow, so it hasn't been split into a separate package (see the "Criteria for placing shared infrastructure" section of [directory-structure.md](directory-structure.md)). The NestJS implementation splits the same role into a separate package, `src/database/` (`DatabaseModule`, `@Global()`) — FastAPI has no concept of a module "registered globally" at all, so placing `engine`/`SessionLocal` as module-level top-level variables and importing them from anywhere already constitutes "global sharing".

## `auth/` follows the same 4-layer structure as `account/`, not a flat structure

This section previously proposed a flat structure such as `auth/jwt_service.py`, `auth/dependencies.py`. **The actually implemented `src/auth/` is not that** — it is split into the same 4 layers as a domain package (`account/`): `domain/`, `application/service/`, `infrastructure/`, `interface/rest/` (see the "Current state" tree above). Being shared code does not relax the layer-separation principle — `auth/` is organized just like a small Bounded Context of its own.

## Structure as domains grow — already confirmed with Card/Payment

Even after `card/` (2nd domain) and `payment/` (3rd domain) were added, `database.py`/`common/`/`config/`/`auth/`/`outbox/` continue to be shared as-is, and the new domains were added side by side with `account/` as the same 4-layer package — this is not a projection but what actually happened.

```
src/
  database.py                        ← unchanged — a single engine (shared connection pool)
  common/                            ← reused as-is by the second domain too
  config/
  auth/                              ← the same 4-layer structure as account/
  outbox/                            ← the second domain also shares the same Outbox table/Poller/Consumer
  account/                           ← domain package
    domain/ application/ infrastructure/ interface/
  user/                              ← second domain package (hypothetical)
    domain/ application/ infrastructure/ interface/
```

## Criteria — which code goes into `src/common/` (or `config/`, `auth/`, `outbox/`)

- **It belongs to no single domain**: it's a purely technical/cross-cutting concern, unrelated to the business rules of `Account` or `User`.
- **Two or more domains actually reuse it**: even if only one domain uses it right now, something clearly slated for future reuse (e.g. an ID generator, an error response builder) can be split out ahead of time — conversely, putting Account-only logic into `common/` prematurely is premature abstraction.
- **The Domain layer must never reference it**: even code under `src/common/` that uses `logging`, `contextvars`, `fastapi`, etc. cannot be imported from `domain/` — the "no middleware/logger use in the Domain layer" principle from [cross-cutting-concerns.md](cross-cutting-concerns.md) applies equally to `src/common/`.

## Difference from NestJS's `@Global()` modules

NestJS declares `database/`, `outbox/`, `auth/` as `@Global()` modules so other modules can inject them directly without `imports` (see [nestjs shared-modules.md](../../../nestjs/docs/architecture/shared-modules.md)). FastAPI has no procedure for "registering globally" at all — because any module can be imported and used immediately in Python, the problem `@Global()` solves (the hassle of having to add a module to `imports` everywhere it's used) doesn't exist in the first place. Instead, each domain's `interface/rest/*_router.py` directly imports the shared code it needs and composes it inside its `Depends` factories.

```python
# src/account/interface/rest/account_router.py — actual code. Directly imports and composes shared code
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user  # shared module in src/auth/
from ....database import get_session                                            # shared infrastructure
```

---

### Related documents

- [directory-structure.md](directory-structure.md) — the "Criteria for placing shared infrastructure" section, the full package tree
- [module-pattern.md](module-pattern.md) — how Python packages replace NestJS modules overall
- [aggregate-id.md](aggregate-id.md) — `common/generate_id.py`
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `common/correlation.py`, the principle forbidding Domain-layer references
- [secret-manager.md](secret-manager.md) — `common/secret_service.py`/`common/aws_secret_service.py`
- [authentication.md](authentication.md) — details of the `auth/` 4-layer structure
- [domain-events.md](domain-events.md) — the actual implementation of the `outbox/` shared package (`outbox_model.py`/`outbox_writer.py`/`outbox_poller.py`/`outbox_consumer.py`/`event_handlers.py`)
