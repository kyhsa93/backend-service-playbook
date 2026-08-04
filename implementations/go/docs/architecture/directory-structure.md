# Directory Structure (Go)

The principle follows the root [directory-structure.md](../../../../docs/architecture/directory-structure.md): a domain-first 4-layer structure, with shared infrastructure placed outside the domain directories. The root document uses the NestJS-style nested structure `<domain>/domain|application|interface|infrastructure/` as its example, but Go uses the inverted structure under `internal/` — **layer at the top level, domain underneath it** — since this fits more naturally with Go's package system (directory = package) and `internal/` visibility rules. This repository's `examples/` actually uses this structure.

---

## Actual tree (`examples/`)

```
cmd/
  server/
    main.go                                  ← entry point, dependency assembly
  healthcheck/
    main.go                                  ← tiny probe binary for the Dockerfile HEALTHCHECK (see container.md)

docs/                                        ← swag-generated OpenAPI output — committed, never hand-edited (see api-documentation.md)
  docs.go
  swagger.json
  swagger.yaml

internal/
  common/                                    ← domain-agnostic shared pure utilities (see shared-modules.md)
    id.go                                    ← common.NewID() — aggregate-id.md
    correlation.go                           ← correlation-ID context helpers

  config/                                    ← config loading/validation split by concern (see config.md)
    database.go
    interest.go
    jwt.go
    llm.go
    rate_limit.go
    secret_service.go
    sqs.go
    tracing.go

  domain/
    account/
      account.go                             ← Aggregate Root (New/Reconstitute + domain methods)
      account_status.go                      ← Status enum (Value Object in nature)
      money.go                               ← Money Value Object
      transaction.go                         ← Transaction Entity (+ TransactionType/TransactionCategory)
      events.go                              ← DomainEvent interface + event structs
      errors.go                              ← sentinel errors (var ErrXxx = errors.New(...))
      repository.go                          ← Repository interface + FindQuery
      transaction_repository.go              ← TransactionRepository interface
      anomaly_detection_service.go           ← Domain Service (withdrawal-anomaly judgment)
      transfer_eligibility_service.go        ← Domain Service (transfer judgment)
      spending_analysis.go / spending_analysis_repository.go
      spending_forecast.go / spending_forecast_repository.go
    card/                                    ← Card Bounded Context (see cross-domain.md)
      card.go                                ← Card Aggregate Root (IssueCard/Suspend/Cancel)
      card_status.go
      errors.go
      repository.go                          ← Repository/Query interface (CQRS split)
    payment/                                 ← Payment Bounded Context (Payment + Refund Aggregates)
      payment.go / refund.go
      refund_eligibility_service.go          ← Domain Service (see domain-service.md at the root)
      events.go / errors.go / status.go / repository.go
    credential/                              ← authentication/signup Aggregate (see authentication.md)
      credential.go                          ← userId + bcrypt hash
      errors.go
      repository.go

  application/
    command/                                 ← one file per Command Handler + the ports they own
      create_account_handler.go, deposit_handler.go, withdraw_handler.go,
      transfer_handler.go, suspend/reactivate/close_account_handler.go, ...
      issue_card_handler.go, suspend/cancel_cards_by_account_handler.go,
      create/cancel_payment_handler.go, request_refund_handler.go,
      apply_daily_interest_handler.go, send_card_usage_statement_handler.go,
      analyze_monthly_spending_handler.go, forecast_spending_handler.go,
      sign_in_handler.go, sign_up_handler.go,
      account_adapter.go, payment_card_adapter.go, ...  ← ACL ports
      transaction_manager.go                 ← TransactionManager port (see persistence.md)
    query/                                   ← one file per Query Handler + Result DTOs
      get_account_handler.go, get_transactions_handler.go, get_card_handler.go,
      get_payment(s)_handler.go, get_refunds_handler.go,
      get_spending_analysis/forecast_handler.go,
      ask_transaction_history_handler.go,    ← LLM-backed structured-data RAG pipeline
      result.go, card_result.go, payment_result.go, ...
    event/                                   ← handlers for events drained by the Outbox (see domain-events.md)
      account_created/money_deposited/money_withdrawn/..._event_handler.go,
      payment_completed/payment_cancelled/refund_approved_event_handler.go,
      categorize_transaction_event_handler.go, detect_withdrawal_anomaly_event_handler.go,
      classify_refund_reason_event_handler.go, interest_paid_event_handler.go,
      integration_publisher.go               ← IntegrationPublisher port
    integration-event/                       ← versioned Integration Event contracts between BCs
      account_suspended/account_closed_integration_event.go,
      payment_completed/payment_cancelled/refund_approved_integration_event.go

  infrastructure/
    persistence/
      account_repository.go                  ← account.Repository implementation (also records Outbox rows in the same transaction)
      transaction_repository.go
      card_repository.go
      credential_repository.go
      payment_repository.go                  ← Payment + Refund (one struct satisfies both interfaces)
      spending_analysis_repository.go / spending_forecast_repository.go
    acl/
      account_adapter.go                     ← command.AccountAdapter implementation (Card→Account ACL, see cross-domain.md)
      card_payment_adapter.go / payment_adapters.go
    auth/                                    ← authentication Technical Service implementation (see authentication.md)
      bcrypt_password_hasher.go
      jwt_service.go
    database/
      transaction.go                         ← WithTx/TxFromContext/QuerierFrom/Manager (see persistence.md)
    forecasting/
      spending_forecast_model.go             ← in-process linear-regression Technical Service
    llm/                                     ← Ollama-backed LLM Technical Services
      ollama_chat.go, nl_transaction_query_translator.go, nl_transaction_answer_composer.go,
      transaction_auto_categorizer.go, refund_reason_classifier.go
    logging/
      correlation.go                         ← slog CorrelationHandler (see observability.md)
    secret/                                  ← Secrets Manager access implementation (see secret-manager.md)
      service.go
    notification/
      service.go                             ← notification sending invoked by event handlers (SES + DB record)
      ses_client.go
    outbox/                                  ← domain-agnostic shared infrastructure (see shared-modules.md)
      writer.go                              ← records Domain Events as Outbox rows within the Repository.Save transaction
      publisher.go                           ← records Integration Events as Outbox rows via EventHandler
      sqs_client.go                          ← creates the SQS client shared by Poller/Consumer
      poller.go                              ← periodically reads unprocessed Outbox rows and publishes them to SQS
      consumer.go                            ← receives from SQS → runs the Handler per event_type
      trace_context.go                       ← W3C traceparent extraction/re-hydration (see observability.md)
    scheduling/                              ← the Cron half — Schedulers that only enqueue Tasks (see scheduling.md)
      interest_scheduler.go, statement_scheduler.go,
      spending_analysis_scheduler.go, spending_forecast_scheduler.go, task_queue.go
    task-queue/                              ← the Task Queue half (Writer/Poller/Consumer), separate table/queue from outbox
      writer.go, poller.go, consumer.go
    tracing/
      provider.go                            ← OTel TracerProvider setup (see observability.md)

  interface/
    http/
      router.go                              ← net/http routing + dependency assembly support
      account_handler.go, card_handler.go, payment_handler.go
      auth_handler.go                        ← POST /auth/sign-in, POST /auth/sign-up
      health_handler.go                      ← /health/live, /health/ready (see graceful-shutdown.md)
      dto.go                                 ← request/response DTOs
      middleware/
        auth_middleware.go, correlation_id_middleware.go, logging_middleware.go,
        metrics_middleware.go, rate_limit_middleware.go, security_headers_middleware.go
    task/                                    ← Task Controllers the task-queue Consumer routes to (see scheduling.md)
      interest_task_controller.go, statement_task_controller.go,
      spending_analysis_task_controller.go, spending_forecast_task_controller.go

migrations/                                  ← every NNNN_*.sql has a paired NNNN_*.down.sql
  0001_init.sql
  0002_add_email_and_sent_emails.sql
  0003_add_outbox.sql
  0004_add_card.sql
  ... (0005–0012: credential, payment, scheduling, outbox trace_parent,
       spending analysis/forecast, transaction merchant/category, refund reason)

test/
  account_e2e_test.go
  auth_e2e_test.go
  card_e2e_test.go                           ← verifies both the synchronous ACL and the async Integration Event reaction
  notification_e2e_test.go
  observability_e2e_test.go
  payment_e2e_test.go
  scheduling_e2e_test.go
  withdrawal_anomaly_e2e_test.go

localstack/
  init-secrets.sh
  init-ses.sh
  init-sqs.sh

Dockerfile
docker-compose.yml
go.mod
```

> `internal/` is a visibility boundary the Go compiler enforces — packages under `internal/` can't be imported from outside the parent directory of `internal/`. Unlike NestJS/Java's `public`/`private` access modifiers, encapsulation here works only at the **package** granularity, so if you want to hide a type internal to a domain from the outside, it must be split into a separate package from the start (see the "Encapsulation limits" section of [tactical-ddd.md](tactical-ddd.md)).

---

## Mapping to the root structure

| Root concept (NestJS style) | Go equivalent |
|---|---|
| `<domain>/domain/` | `internal/domain/<domain>/` |
| `<domain>/application/command/` | `internal/application/command/` (consider subdividing into `command/<domain>/` once multiple domains exist) |
| `<domain>/application/query/` | `internal/application/query/` |
| `<domain>/infrastructure/` | `internal/infrastructure/<concern>/` (sub-packages by concern such as persistence, notification, etc.) |
| `<domain>/interface/` | `internal/interface/http/` |
| `common/` | `internal/common/` (`id.go` — framework-agnostic pure functions such as ID generation) (see [aggregate-id.md](aggregate-id.md)) |
| `database/` (TransactionManager) | `internal/infrastructure/database/` (`WithTx`/`TxFromContext`/`QuerierFrom`/`Manager`) — cross-account Transfer, which bundles multiple Repository saves into one transaction, is the real use case (see [persistence.md](persistence.md)) |
| `outbox/` | `internal/infrastructure/outbox/` — `Writer`/`Poller`/`Consumer` implemented (see [domain-events.md](domain-events.md)) |
| `task-queue/` | `internal/infrastructure/task-queue/` (`Writer`/`Poller`/`Consumer`) — the recurring interest payment / card statement dispatch batch job is the real use case (see [scheduling.md](scheduling.md)) |
| `config/` | `internal/config/` (`database.go`/`jwt.go`/`rate_limit.go`/`secret_service.go`) (see [config.md](config.md)) |

As more domains are added, files grow per domain, like `internal/domain/<domain>/`, `internal/infrastructure/persistence/<domain>_repository.go`. Currently `examples/` has four domain packages — `account`, `card`, `payment` (the three business Bounded Contexts) and `credential` (the authentication Aggregate) — distinguished by filename prefix, and `internal/application/command/`/`query/` still hold every domain's handlers together in a flat structure — once more domains make the files unwieldy, consider splitting into subdirectories like `command/<domain>/`. See [cross-domain.md](cross-domain.md) for how the cross-domain calls between Account, Card, and Payment are placed.

---

## File, package, type naming

| Target | Rule | Example |
|------|------|------|
| File name | `snake_case.go` | `account_repository.go`, `get_transactions_handler.go` |
| Package name | a single lowercase word (no underscores) | `package account`, `package persistence` |
| Type name | `PascalCase` | `Account`, `AccountRepository` |
| Public function/method | `PascalCase` | `New`, `Deposit`, `FindAccounts` |
| Private function/method | `camelCase` | `newTransaction`, `describe` |
| Error | `ErrXxx` | `ErrNotFound`, `ErrInsufficientBalance` |
| Interface | a noun (avoid verb+er, prefer a role name) | `Repository`, `AccountAdapter`, `SESClient` |

The package name matches the directory name (`internal/domain/account/` → `package account`). Multi-word concepts are separated by nesting directories (`application/command/` → `package command`) — Go convention avoids underscores or camelCase in package names.

---

## Shared infrastructure is added only once it's actually needed

Following Go convention, the root's `common/`, `database/`, `outbox/`, `task-queue/`, `config/` directories are each created only once the corresponding pattern (ID utility, transaction propagation, Outbox, Task Queue, config validation) is actually needed — an empty abstraction is never created ahead of time (YAGNI). All five have a real use case: `outbox/` is the real use case for a side effect (notification sending) that must not be lost; `task-queue/` is the real use case for the recurring interest payment / card statement dispatch batch job; `database/` is the real use case where cross-account Transfer bundles two Account saves into one transaction. When new shared infrastructure becomes necessary, follow the same principle (add it only when a real use case exists).

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction and role details
- [repository-pattern.md](repository-pattern.md) — Repository placement rules
- [tactical-ddd.md](tactical-ddd.md) — internal design of the domain package, encapsulation limits
- [config.md](config.md) — the pattern for introducing `internal/config/`
