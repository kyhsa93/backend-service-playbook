# Shared Code Structure (Go)

Go-specific document — there's no corresponding document at the root. NestJS declares `src/common/`, `src/database/`, `src/outbox/`, `src/auth/` as `@Global` modules and injects them into multiple domain modules. Go has no concept of a "global module" — **shared code is just an ordinary package under `internal/`, and "sharing" happens by `main.go` passing the same instance into multiple domains' constructors.**

---

## Current state — `common/`/`config/`/`infrastructure/auth/`/`infrastructure/outbox/` all exist

Looking at the `internal/` tree (see directory-structure.md), there are already several shared packages used together by the Account/Card/Payment/Credential domains:

- **`internal/common/`** (`id.go`) — the `common.NewID()` (UUID v4 with hyphens removed) utility (see [aggregate-id.md](aggregate-id.md)).
- **`internal/config/`** (`database.go`/`jwt.go`/`rate_limit.go`/`secret_service.go`) — config loading/validation split by concern (see [config.md](config.md)).
- **`internal/infrastructure/outbox/`** (`Writer`/`Poller`/`Consumer`) — the Outbox pattern is needed instead of dual-write because sending a notification is a side effect that must not be lost (see [domain-events.md](domain-events.md)).
- **`internal/infrastructure/auth/`** (`bcrypt_password_hasher.go`/`jwt_service.go`), **`internal/infrastructure/secret/`** (`service.go`) — authentication / Secrets Manager Technical Service implementations (see [authentication.md](authentication.md), [secret-manager.md](secret-manager.md)).

This document lays out where this placement gets subdivided as more domains are added.

---

## Actual structure

```
internal/
  common/                          # framework-agnostic pure utilities — owned by no domain
    id.go                          # common.NewID() — aggregate-id.md

  config/                          # config loading/validation split by concern — config.md
    database.go
    jwt.go
    rate_limit.go
    secret_service.go

  infrastructure/
    persistence/
      account_repository.go        # Account-only
      card_repository.go           # Card-only
      credential_repository.go     # Credential-only
    auth/                          # authentication Technical Service implementation — authentication.md
      bcrypt_password_hasher.go
      jwt_service.go
    secret/                        # Secrets Manager access implementation — secret-manager.md
      service.go
    notification/
      service.go                   # Account-only notification implementation
    outbox/                        # OutboxWriter/Poller/Consumer (domain-events.md), shared as more domains are added
      writer.go                    # loads events as outbox rows within the Repository.Save transaction
      poller.go                    # reads the outbox table on an independent tick and publishes to SQS
      consumer.go                  # SQS long polling → handler routing
      publisher.go
      sqs_client.go

  interface/
    http/
      middleware/                  # middleware shared by every domain's router — cross-cutting-concerns.md
        correlation_id_middleware.go
        auth_middleware.go
        rate_limit_middleware.go
      health_handler.go            # liveness/readiness, which belongs to no domain — graceful-shutdown.md

  domain/
    account/                       # Account Bounded Context
    card/                          # Card Bounded Context
    payment/                       # Payment/Refund Bounded Context — EvaluateRefundEligibility (a domain service coordinating multiple Aggregates)
    credential/                    # authentication/signup Aggregate

  application/
    command/                       # holds the three domains' handlers together in a flat structure (consider command/<domain>/ if more domains are added)
    query/
```

- **`internal/common/`** — framework-agnostic pure functions (ID generation, etc.) that any domain can reference. Importing it from the Domain layer doesn't violate Principle 2 (framework-agnosticism) — the `common` package itself contains only functions as pure as the standard library.
- **`internal/interface/http/middleware/`** — HTTP-only shared code whose location is already fixed by [cross-cutting-concerns.md](cross-cutting-concerns.md). One chain is applied to every router instead of being reimplemented per domain.
- **`internal/infrastructure/outbox/`** — the Repositories of the three domains Account/Card/Payment share the same `outbox.Writer` instance, and the single `outbox.Poller`/`outbox.Consumer` that `main.go` assembles share the same shared `map[string]outbox.Handler` (see [domain-events.md](domain-events.md)).
- **`internal/infrastructure/database/`** — `WithTx`/`TxFromContext`/`QuerierFrom`/`Manager` bundle multiple Repository saves into a single DB transaction. Transferring money between accounts (Transfer), which bundles the withdrawal-account save and deposit-account save into one transaction, is the real use case for this (see [persistence.md](persistence.md)).
- **Domain-only code is never moved into a shared package** — an implementation used by only one domain, like `account_repository.go`, stays under that domain's `infrastructure/<concern>/`. "Sharing" only applies once two or more domains actually need the same code (YAGNI).

---

## "Sharing" is passing the same instance, not a declaration

In NestJS, declaring `DatabaseModule` as `@Global()` lets every module have `DataSource` injected without a separate `imports` entry. Go has no such global-scope declaration — sharing happens purely by **`main.go` passing the same value as an argument to multiple constructors**.

```go
// main.go — sharing one db connection pool across two domains' Infrastructure
db, err := sql.Open("postgres", cfg.URL)
// ...

accountRepo := persistence.NewAccountRepository(db)  // Account shares db
userRepo := userpersistence.NewRepository(db)        // User shares the same db

logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
loggingMW := middleware.RequestLogging(logger)       // middleware is also created once and reused across routers
```

There is no special "global" scope — variables like `db` and `logger` simply live in the scope of the `main()` function, and their values are passed as arguments to every constructor that needs them. What NestJS's `@Global` decorator did is replaced in Go by the utterly ordinary approach of "pass that variable as an argument to the functions that know about it." See [module-pattern.md](module-pattern.md) and [bootstrap.md](bootstrap.md) for details.

---

## Principles

- **Create a shared package only when two or more domains (or two or more Aggregate instances) actually need it** — don't create an empty shared package ahead of time. `internal/common/`/`internal/config/` hold code Account/Card/Credential actually share, and `internal/infrastructure/database/` (the shared transaction helper) has cross-account Transfer as its real use case for that scenario.
- **`internal/common/` holds only framework-agnostic pure functions** — never put code that depends on a specific technology like DB or HTTP here (that code goes to `internal/infrastructure/<concern>/`).
- **HTTP-only shared code is gathered in `internal/interface/http/middleware/`** — it is never duplicated across per-domain Handlers.
- **Sharing is wiring, not a declaration** — passing the same instance to multiple constructors in `main.go` is what replaces a "global module."

---

### Related documents

- [directory-structure.md](directory-structure.md) — the current state of shared infrastructure directories and when each one gained a real use case
- [aggregate-id.md](aggregate-id.md) — the actual code in `internal/common/id.go`
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the shared middleware in `internal/interface/http/middleware/`
- [module-pattern.md](module-pattern.md) — package boundaries and the wiring mechanism overall
- [bootstrap.md](bootstrap.md) — the actual order in which `main.go` creates and passes shared instances
