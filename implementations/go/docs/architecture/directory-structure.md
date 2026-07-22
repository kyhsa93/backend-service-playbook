# Directory Structure (Go)

The principle follows the root [directory-structure.md](../../../../docs/architecture/directory-structure.md): a domain-first 4-layer structure, with shared infrastructure placed outside the domain directories. The root document uses the NestJS-style nested structure `<domain>/domain|application|interface|infrastructure/` as its example, but Go uses the inverted structure under `internal/` — **layer at the top level, domain underneath it** — since this fits more naturally with Go's package system (directory = package) and `internal/` visibility rules. This repository's `examples/` actually uses this structure.

---

## Actual tree (`examples/`)

```
cmd/
  server/
    main.go                                  ← entry point, dependency assembly

internal/
  common/                                    ← domain-agnostic shared pure utilities (see shared-modules.md)
    id.go                                    ← common.NewID() — aggregate-id.md

  config/                                    ← config loading/validation split by concern (see config.md)
    database.go
    jwt.go
    rate_limit.go
    secret_service.go

  domain/
    account/
      account.go                             ← Aggregate Root (New/Reconstitute + domain methods)
      account_status.go                      ← Status enum (Value Object in nature)
      money.go                               ← Money Value Object
      transaction.go                         ← Transaction Entity
      events.go                              ← DomainEvent interface + event structs
      errors.go                              ← sentinel errors (var ErrXxx = errors.New(...))
      repository.go                          ← Repository interface + FindQuery
    card/                                    ← second Bounded Context (see cross-domain.md)
      card.go                                ← Card Aggregate Root (IssueCard/Suspend/Cancel)
      card_status.go                         ← Status enum
      errors.go
      repository.go                          ← Repository/Query interface (CQRS split)
    credential/                              ← authentication/signup Aggregate (see authentication.md)
      credential.go                          ← userId + bcrypt hash
      errors.go
      repository.go

  application/
    command/
      create_account_handler.go
      deposit_handler.go
      withdraw_handler.go
      suspend_account_handler.go
      reactivate_account_handler.go
      close_account_handler.go
      account_adapter.go                     ← Card→Account synchronous query port (ACL interface) + AccountView
      issue_card_handler.go                  ← card issuance (synchronously checks account active state via AccountAdapter)
      suspend_cards_by_account_handler.go    ← account.suspended.v1 reaction use case (idempotent)
      cancel_cards_by_account_handler.go     ← account.closed.v1 reaction use case (idempotent)
      sign_in_handler.go                     ← issues a token after comparing against the stored hash (see authentication.md)
      sign_up_handler.go                     ← duplicate check → hashing → save
    query/
      get_account_handler.go
      get_transactions_handler.go
      get_card_handler.go
      result.go                              ← Account Result DTOs
      card_result.go                         ← Card Result DTO
    event/
      account_created_event_handler.go       ← handles events drained by the Outbox to send notifications (see domain-events.md)
      money_deposited_event_handler.go
      money_withdrawn_event_handler.go
      account_suspended_event_handler.go     ← sends notification + records the account.suspended.v1 Integration Event
      account_reactivated_event_handler.go
      account_closed_event_handler.go        ← sends notification + records the account.closed.v1 Integration Event
      integration_publisher.go               ← IntegrationPublisher port (the minimal signature the event package needs)
    integration-event/                       ← versioned Integration Event contracts Account exposes to external BCs
      account_suspended_integration_event.go
      account_closed_integration_event.go

  infrastructure/
    persistence/
      account_repository.go                  ← account.Repository implementation (also records Outbox rows in the same transaction)
      card_repository.go                     ← card.Repository implementation
      credential_repository.go               ← credential.Repository implementation
    acl/
      account_adapter.go                     ← command.AccountAdapter implementation (Card→Account ACL, see cross-domain.md)
    auth/                                    ← authentication Technical Service implementation (see authentication.md)
      bcrypt_password_hasher.go
      jwt_service.go
    secret/                                  ← Secrets Manager access implementation (see secret-manager.md)
      service.go
    notification/
      service.go                             ← notification sending invoked by event handlers (SES + DB record)
      ses_client.go                          ← SES client creation
    outbox/                                  ← domain-agnostic shared infrastructure (see shared-modules.md)
      writer.go                              ← records Domain Events as Outbox rows within the Repository.Save transaction
      publisher.go                           ← records Integration Events as Outbox rows via EventHandler
      sqs_client.go                          ← creates the SQS client shared by Poller/Consumer
      poller.go                              ← periodically reads unprocessed Outbox rows and publishes them to SQS
      consumer.go                            ← receives from SQS → runs the Handler per event_type

  interface/
    http/
      router.go                              ← net/http routing + dependency assembly support
      account_handler.go                      ← HTTP handler
      card_handler.go                        ← Card HTTP handler (POST /cards, GET /cards/{cardId})
      auth_handler.go                        ← POST /auth/sign-in, POST /auth/sign-up
      dto.go                                  ← request/response DTOs

migrations/
  0001_init.sql
  0002_add_email_and_sent_emails.sql
  0003_add_outbox.sql
  0004_add_card.sql

test/
  account_e2e_test.go
  notification_e2e_test.go
  card_e2e_test.go                           ← verifies both the synchronous ACL and the async Integration Event reaction

localstack/
  init-ses.sh

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

As more domains are added, files grow per domain, like `internal/domain/<domain>/`, `internal/infrastructure/persistence/<domain>_repository.go`. Currently `examples/` has two Bounded Contexts, Account and Card (distinguished by the `card_*` filename prefix), and `internal/application/command/`/`query/` still hold both domains' handlers together in a flat structure — once more domains make the files unwieldy, consider splitting into subdirectories like `command/<domain>/`. See [cross-domain.md](cross-domain.md) for how the Account↔Card cross-domain call is placed.

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
