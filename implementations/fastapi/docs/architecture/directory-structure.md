# Directory Structure

> Framework-agnostic principles: [../../../../docs/architecture/directory-structure.md](../../../../docs/architecture/directory-structure.md)

This is the FastAPI package structure, based on this repository's actual implementation (`examples/src/account/`).

## Overall structure

```
implementations/fastapi/examples/
  main.py                              ← creates the FastAPI app, validate_env(), lifespan, router registration,
                                          correlation/rate-limit/security-headers middleware, exception_handler
  requirements.txt
  pytest.ini
  alembic.ini                          ← Alembic migration configuration
  Dockerfile                           ← multi-stage build (container.md)
  .dockerignore
  docker-compose.yml                   ← local infrastructure: database (Postgres), localstack (SES + Secrets Manager + SQS),
                                          ollama + ollama-init (local LLM), app profile (local-dev.md)
  .env.example                         ← the committed environment-variable template
  .gitignore                           ← excludes local-only files such as .env*
  localstack/
    init-ses.sh                        ← LocalStack SES initialization script
    init-secrets.sh                    ← LocalStack Secrets Manager initialization script (app/jwt)
    init-sqs.sh                        ← LocalStack SQS initialization script (domain-events + tasks.fifo/tasks-dlq.fifo)
  migrations/                          ← Alembic migrations
    env.py
    script.py.mako
    versions/                          ← one file per schema change — many files accumulate here
      110ed0152981_create_initial_tables.py
      3b20c155767c_create_cards_table.py
      8f1c2a4e6d90_add_scheduling_task_queue_tables.py
      ...
  src/
    database.py                        ← engine/session factory, the get_session() dependency (uses DatabaseConfig)

    common/                            ← shared utils/infrastructure that don't belong to any domain (shared-modules.md)
      generate_id.py                   ← generate_id() — generates a UUID hex ID
      logging_config.py                ← JsonFormatter, configure_logging()
      correlation.py                   ← contextvars-based Correlation ID
      error_response.py                ← ErrorResponse, build_error_response() (error-handling.md)
      rate_limit.py                    ← the shared slowapi limiter (rate-limiting.md)
      security_headers.py              ← apply_security_headers()
      tracing.py                       ← configure_tracing(), shutdown_tracing() (observability.md)
      secret_service.py                ← SecretService ABC
      aws_secret_service.py            ← AwsSecretService(SecretService) — TTL cache

    config/                            ← per-concern configuration classes, fail-fast validation (config.md)
      database_config.py               ← DatabaseConfig(BaseSettings) — DATABASE_URL required
      jwt_config.py                    ← JwtConfig
      aws_config.py                    ← AwsConfig — endpoint/credentials for SES/Secrets Manager/SQS
      sqs_config.py                    ← SqsConfig — queue URLs
      rate_limit_config.py             ← RateLimitConfig — read/write limits
      interest_config.py               ← InterestConfig — the interest-batch rate
      llm_config.py                    ← LlmConfig — the Ollama endpoint/model
      tracing_config.py                ← TracingConfig — the OTLP endpoint
      validator.py                     ← validate_env()

    auth/                              ← shared authentication (authentication.md) — the same 4-layer structure as account/
      domain/
        credential.py                  ← Credential Aggregate
        errors.py                      ← InvalidTokenError, InvalidCredentialsError, ...
        token.py                       ← TokenPayload
        repository.py                  ← CredentialRepository ABC
      application/
        command/
          sign_up_handler.py / sign_in_handler.py
        service/
          auth_service.py               ← AuthService ABC (Technical Service interface)
          password_hasher.py            ← PasswordHasher ABC
      infrastructure/
        jwt_auth_service.py             ← JwtAuthService(AuthService), set_jwt_secret()
        persistence/credential_repository.py
        security/bcrypt_password_hasher.py
      interface/
        rest/
          auth_router.py                ← POST /auth/sign-up, /auth/sign-in
          dependencies.py                ← get_current_user(), CurrentUser
          schemas.py

    outbox/                            ← the shared Outbox pattern (domain-events.md)
      outbox_model.py                  ← OutboxModel(Base)
      outbox_writer.py                  ← OutboxWriter — called by Repository.save_<noun>() in the same session
      outbox_poller.py                  ← OutboxPoller — publishes Outbox → SQS, started as a background task by main.py's lifespan
      outbox_consumer.py                ← OutboxConsumer — receives SQS → EventHandler, also a background task
      event_handlers.py                 ← build_event_handlers() — assembles the eventType → list-of-handlers dict (composition root)

    task_queue/                        ← the shared Task Outbox pattern (scheduling.md)
      task_outbox_model.py             ← TaskOutboxModel(Base)
      task_outbox_writer.py            ← TaskOutboxWriter — enqueues a Task in the same session
      task_outbox_poller.py            ← TaskOutboxPoller — publishes Task Outbox → the tasks.fifo queue
      task_consumer.py                 ← TaskConsumer — receives the Task queue → TaskController
      task_handlers.py                 ← build_task_handlers() — the taskType → TaskController composition root

    account/                           ← a package per Bounded Context (domain)
      domain/                          ← framework-agnostic
        account.py                     ← Aggregate Root
        transaction.py                 ← Entity (frozen dataclass, child object)
        money.py                       ← Value Object (frozen dataclass)
        account_status.py              ← domain enum
        events.py                      ← Domain Events (a collection of frozen dataclasses)
        errors.py                      ← the domain exception hierarchy (AccountError and its subclasses)
        repository.py                  ← AccountQuery ABC + AccountRepository ABC
        transaction_repository.py      ← TransactionRepository ABC
        spending_analysis.py / spending_forecast.py (+ their repository ABCs)
        anomaly_detection_service.py   ← Domain Service
        transfer_eligibility_service.py ← Domain Service

      application/
        command/
          create_account_handler.py    ← CreateAccountCommand + CreateAccountHandler
          deposit_handler.py / withdraw_handler.py / transfer_handler.py
          suspend_account_handler.py / reactivate_account_handler.py / close_account_handler.py
          deposit_by_payment_handler.py / withdraw_by_payment_handler.py
          apply_daily_interest_handler.py / analyze_monthly_spending_handler.py / forecast_spending_handler.py
        event/                         ← an EventHandler per event_type, deserializing the Outbox payload
          account_created_event_handler.py
          account_closed_event_handler.py (and one per remaining Account event)
          categorize_transaction_event_handler.py / detect_withdrawal_anomaly_event_handler.py
        integration_event/             ← this BC's published Integration Event definitions
          account_suspended_integration_event.py / account_closed_integration_event.py
        query/
          get_account_handler.py       ← GetAccountQuery + GetAccountHandler
          get_transactions_handler.py / get_spending_analysis_handler.py / get_spending_forecast_handler.py
          ask_transaction_history_handler.py
          result.py                    ← response DTOs such as GetAccountResult, GetTransactionsResult
        service/
          notification_service.py      ← NotificationService ABC (Technical Service interface)
          transaction_auto_categorizer.py / spending_forecast_model.py
          nl_transaction_query_translator.py / nl_transaction_answer_composer.py

      infrastructure/
        persistence/
          account_repository.py        ← Base(DeclarativeBase), AccountModel, TransactionModel,
                                          SqlAlchemyAccountRepository(AccountRepository)
          transaction_repository.py / spending_analysis_repository.py / spending_forecast_repository.py
        notification/
          notification_service.py      ← SesNotificationService(NotificationService) — an aioboto3 SES implementation
          sent_email_model.py           ← SentEmailModel (the send-history table)
        scheduling/                    ← APScheduler Cron triggers (scheduling.md)
          interest_scheduler.py / spending_analysis_scheduler.py / spending_forecast_scheduler.py
        transaction_auto_categorizer_impl.py / spending_forecast_model_impl.py
        nl_transaction_query_translator_impl.py / nl_transaction_answer_composer_impl.py

      interface/
        rest/
          account_router.py             ← APIRouter(dependencies=[Depends(get_current_user)]), Depends assembly, calls the Handler
          schemas.py                    ← Pydantic request/response models
        integration_event/
          account_integration_event_controller.py ← reacts to other BCs' Integration Events
        task/
          account_task_controller.py    ← the Task-queue entry point (scheduling.md)

    card/                              ← the second Bounded Context — the same 4-layer structure as account/,
      application/adapter/             ← plus application/adapter/ (AccountAdapter/PaymentAdapter ABCs — cross-domain.md),
      infrastructure/scheduling/       ← the statement_scheduler.py Cron trigger,
      interface/integration_event/     ← card_integration_event_controller.py,
      interface/task/                  ← card_task_controller.py

    payment/                           ← the third Bounded Context — the same 4-layer structure, with
      application/adapter/             ← AccountAdapter/CardAdapter ABCs,
      application/integration_event/   ← the PaymentCompleted/PaymentCancelled/RefundApproved Integration Events,
      domain/                          ← Payment + Refund Aggregates, RefundEligibilityService (Domain Service)

  tests/
    conftest.py                        ← sets a DATABASE_URL default (to bypass validate_env()'s fail-fast)
    unit/
      domain/
        test_account.py                ← Domain unit tests (one per Aggregate/VO/Domain Service, all three BCs)
        test_money.py
        ...
      application/
        test_create_account_handler.py ← Application unit tests (mock-based)
        test_deposit_handler.py
        ...
      infrastructure/
        test_transaction_auto_categorizer_impl.py  ← infrastructure-implementation unit tests
        ...
    test_account_e2e.py                ← E2E (testcontainers Postgres)
    test_auth_e2e.py
    test_card_e2e.py
    test_payment_e2e.py
    test_scheduling_e2e.py
    test_notification_e2e.py           ← E2E (testcontainers Postgres + LocalStack SES)
```

---

## An example of Technical Service separation — `notification_service`

This is the representative example of the **Technical Service pattern** from [domain-service.md](../../../../docs/architecture/domain-service.md) (an interface in Application, an implementation in Infrastructure). The same structure is applied to the other technical concerns as well — `auth_service.py`/`password_hasher.py` (auth), `transaction_auto_categorizer.py`/`spending_forecast_model.py`/`nl_transaction_query_translator.py`/`nl_transaction_answer_composer.py` (account), and `refund_reason_classifier.py` (payment). When adding a new technical infrastructure concern (file storage, etc.), follow this same structure.

```python
# application/service/notification_service.py — the interface (ABC)
from abc import ABC, abstractmethod

from ...domain.account import AccountDomainEvent


class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — the implementation (SES + aioboto3)
class SesNotificationService(NotificationService):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None: ...
```

The event handlers in `application/event/` receive the interface (`NotificationService`) in their constructors, not the concrete class (`SesNotificationService`). The actual implementation is bound in `build_event_handlers()` (`src/outbox/event_handlers.py`) — the composition root that assembles the handlers `OutboxConsumer` invokes (see [domain-events.md](domain-events.md)). For dependencies a route receives, the `Depends` factory functions in `interface/rest/*_router.py` (`_repo`, `_query_repo`) serve as the "binding point" — in FastAPI, which has no DI container, the factory function itself is the binding.

---

## Principles per layer

### domain/

- **Framework-agnostic**: imports no external library such as `fastapi`, `sqlalchemy`, `aioboto3`. Only the standard library (`dataclasses`, `abc`, `datetime`, `enum`) is used.
- **Business rules are encapsulated**: state is changed and invariants are validated only inside methods such as `Account`'s `deposit()`/`withdraw()`/`close()`.
- Only the Repository's **ABC** lives here (`repository.py`). The implementation lives in `infrastructure/`.

### application/

- The **orchestrator** of the use case. The Handlers in `command/` and `query/` don't perform business logic directly — they delegate to the Aggregate.
- `service/`: technical infrastructure interfaces (ABCs) — `notification_service.py`, `transaction_auto_categorizer.py`, `spending_forecast_model.py`, `nl_transaction_query_translator.py`, `nl_transaction_answer_composer.py` in account (auth and payment have their own). As new technical concerns are added, more interfaces are added to the same directory.

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

`common/`, `config/`, `auth/`, `outbox/`, `task_queue/` are shared packages used together by the `account`, `card`, and `payment` domains — because the principle of placing a purely technical/cross-cutting concern outside any domain package, regardless of the number of domains, was applied from the very start.

| Directory | Contents |
|---------|----------|
| `src/common/` | Pure utils/infrastructure — `generate_id.py` ([aggregate-id.md](aggregate-id.md)), `logging_config.py`/`correlation.py`/`tracing.py` ([observability.md](observability.md)), `error_response.py` ([error-handling.md](error-handling.md)), `rate_limit.py` ([rate-limiting.md](rate-limiting.md)), `security_headers.py`, `secret_service.py`/`aws_secret_service.py` ([secret-manager.md](secret-manager.md)) |
| `src/config/` | Per-concern configuration classes, fail-fast validation — `database_config.py`/`jwt_config.py`/`aws_config.py`/`sqs_config.py`/`rate_limit_config.py`/`interest_config.py`/`llm_config.py`/`tracing_config.py`, `validator.py` (see [config.md](config.md)) |
| `src/auth/` | Shared authentication — the same 4-layer structure as `account/` (see [authentication.md](authentication.md)) |
| `src/outbox/` | The shared Outbox pattern (see [domain-events.md](domain-events.md)) |
| `src/task_queue/` | The shared Task Outbox pattern (see [scheduling.md](scheduling.md)) |
| `src/database.py` | The DB engine/session factory — kept at its current location (a single module file is sufficient) |

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction and responsibilities in detail
- [repository-pattern.md](repository-pattern.md) — Repository pattern details
- [domain-events.md](domain-events.md) — Domain Event, the Outbox structure
- [config.md](config.md) — environment configuration management
