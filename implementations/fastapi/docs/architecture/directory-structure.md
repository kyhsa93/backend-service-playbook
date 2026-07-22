# Directory Structure

> Framework-agnostic principles: [../../../../docs/architecture/directory-structure.md](../../../../docs/architecture/directory-structure.md)

This is the FastAPI package structure, based on this repository's actual implementation (`examples/src/account/`).

## Overall structure

```
implementations/fastapi/examples/
  main.py                              ← creates the FastAPI app, validate_env(), lifespan, router registration,
                                          correlation_id_middleware, exception_handler
  requirements.txt
  pytest.ini
  alembic.ini                          ← Alembic migration configuration
  Dockerfile                           ← multi-stage build (container.md)
  .dockerignore
  docker-compose.yml                   ← local infrastructure (Postgres + LocalStack(SES, Secrets Manager) + app profile)
  .env.example                         ← the committed environment-variable template
  .gitignore                           ← excludes local-only files such as .env*
  localstack/
    init-ses.sh                        ← LocalStack SES initialization script
    init-secrets.sh                    ← LocalStack Secrets Manager initialization script (app/jwt)
  migrations/                          ← Alembic migrations
    env.py
    script.py.mako
    versions/
      110ed0152981_create_initial_tables.py
  src/
    database.py                        ← engine/session factory, the get_session() dependency (uses DatabaseConfig)

    common/                            ← shared utils/infrastructure that don't belong to any domain (shared-modules.md)
      generate_id.py                   ← generate_id() — generates a UUID hex ID
      logging_config.py                ← JsonFormatter, configure_logging()
      correlation.py                   ← contextvars-based Correlation ID
      secret_service.py                ← SecretService ABC
      aws_secret_service.py            ← AwsSecretService(SecretService) — TTL cache

    config/                            ← per-concern configuration classes, fail-fast validation (config.md)
      database_config.py               ← DatabaseConfig(BaseSettings) — DATABASE_URL required
      validator.py                     ← validate_env()

    auth/                              ← shared authentication (authentication.md) — the same 4-layer structure as account/
      domain/
        errors.py                      ← InvalidTokenError
        token.py                       ← TokenPayload
      application/
        service/
          auth_service.py               ← AuthService ABC (Technical Service interface)
      infrastructure/
        jwt_auth_service.py             ← JwtAuthService(AuthService), set_jwt_secret()
      interface/
        rest/
          auth_router.py                ← POST /auth/sign-in
          dependencies.py                ← get_current_user(), CurrentUser
          schemas.py

    outbox/                            ← the shared Outbox pattern (domain-events.md)
      outbox_model.py                  ← OutboxModel(Base)
      outbox_writer.py                  ← OutboxWriter — called by Repository.save() in the same session
      outbox_poller.py                  ← OutboxPoller — publishes Outbox → SQS, started as a background task by main.py's lifespan
      outbox_consumer.py                ← OutboxConsumer — receives SQS → EventHandler, also a background task
      event_handlers.py                 ← build_event_handlers() — assembles the eventType → handler dict (composition root)

    account/                           ← a package per Bounded Context (domain)
      domain/                          ← framework-agnostic
        account.py                     ← Aggregate Root
        transaction.py                 ← Entity (frozen dataclass, child object)
        money.py                       ← Value Object (frozen dataclass)
        account_status.py              ← domain enum
        events.py                      ← Domain Events (a collection of frozen dataclasses)
        errors.py                      ← the domain exception hierarchy (AccountError and its subclasses)
        repository.py                  ← AccountRepository ABC

      application/
        command/
          create_account_handler.py    ← CreateAccountCommand + CreateAccountHandler
          deposit_handler.py
          withdraw_handler.py
          suspend_account_handler.py
          reactivate_account_handler.py
          close_account_handler.py
        event/                         ← an EventHandler per event_type, deserializing the Outbox payload
          account_created_event_handler.py
          account_closed_event_handler.py
          account_reactivated_event_handler.py
          account_suspended_event_handler.py
          money_deposited_event_handler.py
          money_withdrawn_event_handler.py
        query/
          get_account_handler.py       ← GetAccountQuery + GetAccountHandler
          get_transactions_handler.py
          result.py                    ← response DTOs such as GetAccountResult, GetTransactionsResult
        service/
          notification_service.py      ← NotificationService ABC (Technical Service interface)

      infrastructure/
        persistence/
          account_repository.py        ← Base(DeclarativeBase), AccountModel, TransactionModel,
                                          SqlAlchemyAccountRepository(AccountRepository)
        notification/
          notification_service.py      ← SesNotificationService(NotificationService) — an aioboto3 SES implementation
          sent_email_model.py           ← SentEmailModel (the send-history table)

      interface/
        rest/
          account_router.py             ← APIRouter(dependencies=[Depends(get_current_user)]), Depends assembly, calls the Handler
          schemas.py                    ← Pydantic request/response models

  tests/
    conftest.py                        ← sets a DATABASE_URL default (to bypass validate_env()'s fail-fast)
    unit/
      domain/
        test_account.py                ← Domain unit tests
        test_money.py
      application/
        test_create_account_handler.py ← Application unit tests (mock-based)
        test_deposit_handler.py
    test_account_e2e.py                ← E2E (testcontainers Postgres)
    test_notification_e2e.py           ← E2E (testcontainers Postgres + LocalStack SES)
```

---

## An example of Technical Service separation — `notification_service`

This is the only place in this repository where the **Technical Service pattern** from [domain-service.md](../../../../docs/architecture/domain-service.md) (an interface in Application, an implementation in Infrastructure) is actually applied. When adding a new technical infrastructure concern (file storage, Secrets Manager, etc.), follow this same structure.

```python
# application/service/notification_service.py — the interface (ABC)
from abc import ABC, abstractmethod

from ...domain.account import AccountDomainEvent


class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — the implementation (SES + aioboto3)
class SesNotificationService(NotificationService):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: AccountDomainEvent) -> None:
        ...
```

`application/command/deposit_handler.py` receives the interface (`NotificationService`) in its constructor, not the concrete class (`SesNotificationService`). The `_notification_service()` factory in `interface/rest/account_router.py` binds the actual implementation via `Depends` — in FastAPI, which has no DI container, this factory function itself serves as the "binding point."

---

## Principles per layer

### domain/

- **Framework-agnostic**: imports no external library such as `fastapi`, `sqlalchemy`, `aioboto3`. Only the standard library (`dataclasses`, `abc`, `datetime`, `enum`) is used.
- **Business rules are encapsulated**: state is changed and invariants are validated only inside methods such as `Account`'s `deposit()`/`withdraw()`/`close()`.
- Only the Repository's **ABC** lives here (`repository.py`). The implementation lives in `infrastructure/`.

### application/

- The **orchestrator** of the use case. The Handlers in `command/` and `query/` don't perform business logic directly — they delegate to the Aggregate.
- `service/`: technical infrastructure interfaces (ABCs) — currently there's just one, `notification_service.py`. As file storage, Secrets Manager, etc. are added, more interfaces are added to the same directory.

### interface/rest/

- The external entry point. `account_router.py` converts an HTTP request into a Command/Query and delegates to the Handler.
- The Pydantic models in `schemas.py` are dedicated to the request/response schema, doing only thin conversion that wraps the Result object from `application/query/result.py`.

### infrastructure/

- The only layer that actually accesses external systems. `persistence/` uses SQLAlchemy directly, `notification/` uses aioboto3 (SES) directly.
- The ABC implementations for `domain/repository.py` and `application/service/notification_service.py` all live here.

---

## File/module naming

| Target | Rule | Example |
|------|------|------|
| File/module names | `snake_case.py` | `account_repository.py` |
| Package (directory) names | lowercase | `domain`, `persistence`, `notification` |
| Class names | `PascalCase` | `Account`, `AccountRepository`, `SesNotificationService` |
| Functions/variables | `snake_case` | `create_account`, `pull_events` |
| Constants | `UPPER_SNAKE_CASE` | `DEFAULT_SENDER_EMAIL` |
| Exception classes | `PascalCase` + `Error` | `AccountNotFoundError` |
| ABC (interface) | never includes the implementation technology in its name | `AccountRepository`, `NotificationService` |
| ABC implementation | prefixed with the implementation technology | `SqlAlchemyAccountRepository`, `SesNotificationService` |

---

## Class naming rules

| Kind | Rule | Example |
|------|------|------|
| Aggregate Root | a domain noun (PascalCase) | `Account` |
| Entity (child object) | a domain noun | `Transaction` |
| Value Object | a domain concept | `Money` |
| Domain Event | past-tense PascalCase | `AccountCreated`, `MoneyDeposited` |
| Repository interface | `<Aggregate>Repository` | `AccountRepository` |
| Repository implementation | `SqlAlchemy<Aggregate>Repository` | `SqlAlchemyAccountRepository` |
| Command | `<Verb><Noun>Command` | `DepositCommand` |
| CommandHandler | `<Verb><Noun>Handler` | `DepositHandler` |
| Query | `<Verb><Noun>Query` | `GetAccountQuery` |
| Result | `<Verb><Noun>Result`/`<Noun>Result` | `GetAccountResult`, `MoneyResult` |
| Technical Service interface | `<Concern>Service` | `NotificationService` |
| Technical Service implementation | `<Provider><Concern>Service` | `SesNotificationService` |

---

## Criteria for placing shared infrastructure

`common/`, `config/`, `auth/`, `outbox/` are shared packages used together by both the `account` and `card` domains — because the principle of placing a purely technical/cross-cutting concern outside any domain package, regardless of the number of domains, was applied from the very start.

| Directory | Contents |
|---------|----------|
| `src/common/` | Pure utils/infrastructure — `generate_id.py` ([aggregate-id.md](aggregate-id.md)), `logging_config.py`/`correlation.py` ([observability.md](observability.md)), `secret_service.py`/`aws_secret_service.py` ([secret-manager.md](secret-manager.md)) |
| `src/config/` | Per-concern configuration classes, fail-fast validation — `database_config.py`/`jwt_config.py`/`aws_config.py`/`rate_limit_config.py`, `validator.py` (see [config.md](config.md)) |
| `src/auth/` | Shared authentication — the same 4-layer structure as `account/` (see [authentication.md](authentication.md)) |
| `src/outbox/` | The shared Outbox pattern (see [domain-events.md](domain-events.md)) |
| `src/database.py` | The DB engine/session factory — kept at its current location (a single module file is sufficient) |

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction and responsibilities in detail
- [repository-pattern.md](repository-pattern.md) — Repository pattern details
- [domain-events.md](domain-events.md) — Domain Event, the Outbox structure
- [config.md](config.md) — environment configuration management
