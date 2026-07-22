# Go Implementation Guide

Design/implementation guide for a DDD-based Go backend service.
Follows the 4-layer `internal/{domain,application,infrastructure,interface}/` structure.

> For **framework-agnostic design principles**, see the root [CLAUDE.md](../../CLAUDE.md) and `../../docs/architecture/`.
> This document focuses on Go implementation details.

## Documents to consult while working

### Design / structure

| Task / keyword | Document to read |
|---------------|----------|
| Strategic design, Subdomain, Bounded Context, Context Map, Ubiquitous Language | `../../docs/architecture/strategic-ddd.md` (shared root document) |
| Project structure, directory layout, `internal/` package placement | `docs/architecture/directory-structure.md` |
| Layer roles, Domain / Application / Interface / Infrastructure, dependency direction | `docs/architecture/layer-architecture.md` |
| Aggregate Root, Entity, Value Object, Domain Event modeling, struct + methods, encapsulation limits | `docs/architecture/tactical-ddd.md` |
| Separating Repository interface from implementation, compile-time interface verification | `docs/architecture/repository-pattern.md` |
| Aggregate ID generation rules, 32-character hex | `docs/architecture/aggregate-id.md` |
| Core design principles TL;DR, checklist summary | `docs/architecture/design-principles.md` |
| Dependency assembly without a DI container, package = module boundary, avoiding circular dependencies | `docs/architecture/module-pattern.md` |
| Cross-domain calls, Adapter pattern implementation, examples of calling other Bounded Contexts | `docs/architecture/cross-domain.md` |
| Location of shared code, `internal/common/`, shared infrastructure placement | `docs/architecture/shared-modules.md` |
| Adding a new domain, domain scaffolding template, Order example | `docs/reference.md` — `scripts/create-domain` can instantly generate the `docs/reference.md` template as real code (see "Scaffolding" below) |

### Data / transactions

| Task / keyword | Document to read |
|---------------|----------|
| Transaction propagation, `context.Context`, Unit of Work, Soft Delete, migrations | `docs/architecture/persistence.md` |
| Domain Event, event collection, Outbox pattern, avoiding dual-write | `docs/architecture/domain-events.md` |
| CQRS, Command/Query Handler, `Handle(ctx, cmd/query)` pattern | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| Task / keyword | Document to read |
|---------------|----------|
| REST endpoints, `net/http` handlers, DTOs | `docs/architecture/api-response.md` |
| Response format, Pagination, `page`/`take` | `docs/architecture/api-response.md` |
| Authentication, JWT, Bearer tokens, middleware-based Guard replacement | `docs/architecture/authentication.md` |
| Middleware chain, correlation ID injection, request validation | `docs/architecture/cross-cutting-concerns.md` |
| Error handling, sentinel errors, `errors.Is`, HTTP status code mapping | `docs/architecture/error-handling.md` |
| Rate limiting, token bucket, `golang.org/x/time/rate`, request throttling | `docs/architecture/rate-limiting.md` |

### Operations / infrastructure

| Task / keyword | Document to read |
|---------------|----------|
| Environment configuration, env var validation, fail-fast, config structs | `docs/architecture/config.md` |
| Secret management, AWS Secrets Manager, TTL cache | `docs/architecture/secret-manager.md` |
| Container image, Dockerfile, multi-stage build, single static binary | `docs/architecture/container.md` |
| Graceful shutdown, `signal.NotifyContext`, `http.Server.Shutdown` | `docs/architecture/graceful-shutdown.md` |
| App entry point, `main.go`, dependency assembly order, server startup | `docs/architecture/bootstrap.md` |
| Local development environment, docker-compose, LocalStack | `docs/architecture/local-dev.md` |
| Logging, `log/slog`, structured logs, correlation ID propagation | `docs/architecture/observability.md` |
| File storage, presigned URLs, S3 SDK v2 | `docs/architecture/file-storage.md` |

### Async / task queue / scheduling

| Task / keyword | Document to read |
|---------------|----------|
| Scheduling, `time.Ticker`, batch jobs, Task Queue, Task Outbox | `docs/architecture/scheduling.md` |

### Quality / verification

| Task / keyword | Document to read |
|---------------|----------|
| Testing, table-driven tests, Domain/Application/E2E 3 tiers, testcontainers-go | `docs/architecture/testing.md` |
| Running the harness, rule list | `harness/README.md` |
| Harness design principles (evaluates only architectural rule compliance, not business domain knowledge) | `../../docs/harness.md` (shared root document) |
| Lint, how to run golangci-lint, code quality rules | see the "Lint" section below |

## Verifying an implementation

```bash
./harness.sh <projectRoot>
```

FAIL entries include a `ruleId` and a message with a link to the relevant document. Open that document to fix the issue.

## Lint

`examples/` (the app) and `harness/` (the evaluator) are separate Go modules, but they share a single `.golangci.yml` config placed in `implementations/go/` — golangci-lint walks up from the run directory looking for a config file, so running it from under either module picks up the same config.

The linters enabled are just golangci-lint's default set (`errcheck`, `govet`, `ineffassign`, `staticcheck`, `unused`) plus `unconvert` (detects unnecessary type conversions) and the `gofmt` formatter — overly strict rule sets like `revive`/`stylecheck` are not enabled (to avoid unnecessarily large diffs).

```bash
# Local install (https://golangci-lint.run/welcome/install/)
go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest

# Run — from each module separately
(cd examples && golangci-lint run ./...)
(cd harness && golangci-lint run ./...)
```

CI (`.github/workflows/go.yml`) checks both modules separately via `golangci/golangci-lint-action`, and the build fails on any violation.

## Scaffolding — new domain generator

A Go program (`scripts/create-domain/`) that takes the "Practical Implementation Template" (the Order example) from `docs/reference.md`, turns it into real code that passes the entire harness, and then generalizes it into a reusable form by parameterizing only the domain name. It generates, in one pass: an Aggregate (a single Status field + PENDING/ACTIVE/CANCELLED), CQRS Command/Query Handlers, one domain event, a Repository (domain interface + infrastructure implementation), an HTTP Handler/DTO, and a migration.

Like `examples`/`harness`, `scripts/create-domain/` is an independent Go module (with its own go.mod) — since `implementations/go/` has no go.mod/go.work tying them together, it must be run with `go run .` from inside `scripts/create-domain/` (running `go run ./scripts/create-domain` from `implementations/go/` fails with "go.mod file not found").

```bash
cd scripts/create-domain

# Default: generates under examples/internal/..., doesn't touch main.go/router.go,
# just prints to the console the content you should paste in
go run . Coupon

# With --wire, it also auto-inserts into cmd/server/main.go (repository assembly +
# registration in the handlers map shared by outbox.Poller/outbox.Consumer) and
# internal/interface/http/router.go (Handler assembly + route registration)
go run . Coupon --wire

# To generate into a different project (e.g. one cloned from this repo as a template), specify --out
go run . Coupon --out /path/to/other-project --wire
```

Just as NestJS doesn't have a dedicated Relay/Consumer per domain, Go doesn't have a dedicated type per domain either — every domain registers its events together in the **single shared** `map[string]outbox.Handler` that `main.go` assembles, and this map is shared between `outbox.Poller` (Outbox → SQS publish, `internal/infrastructure/outbox/poller.go`) and `outbox.Consumer` (SQS → Handler execution, `internal/infrastructure/outbox/consumer.go`). The Command Handler never references this map at all — it returns immediately after saving (synchronous draining is prohibited, see domain-events.md). That's why `--wire` patches both `main.go` (and, if an e2e test reassembles dependencies in the same shape as `main.go`, that file too) and `router.go` together.

Right after generation, verify with `./harness.sh <projectRoot>` — this was actually tested against new domains unrelated to Account/Card (including multi-word/irregular-plural domains like Coupon, LoyaltyCategory), confirming the harness passes in full and `go build`/`go vet`/`golangci-lint run` produce no warnings. Since it uses a naive pluralization rule (+s/+es/y→ies), domains with irregular plurals may need manual touch-up of generated names such as table names and REST paths (`<domains>Lower`). There's a known gap: the shared `outbox.Writer` (SaveAll) is currently fixed to the `account.DomainEvent` slice type, so other domains can't reuse it as-is — the generated Repository works around this by loading Outbox rows directly within the same transaction (see the comment in the generated `<domain>_repository.go`). What gets generated is a structural skeleton (an empty CRUD-style starting point), so the actual business rules, error messages, and fields need to be filled in by hand after generation.

## Example code (full Account domain)

The `examples/` directory contains a full implementation example of the Account domain (account opening/deposit/withdrawal/suspend/resume/close + email notifications).
Use this as a template when working on a new domain (just change the domain name).
