# Harness — checks Go project structure/naming rules

A static analysis tool that applies the **mechanically verifiable** items among the guide rules in `docs/` (shared) + `docs/architecture/*.md` (Go implementation) to an external Go project. Its design principles follow the root [`../../../docs/harness.md`](../../../docs/harness.md) — it only evaluates architectural rule compliance and does not assume knowledge of any specific business domain, such as the Account domain in `examples/`.

## Structure

```
harness/
  main.go                        CLI entry point — defines the rule list + aggregates/prints results
  rule.go                        Shared types (Finding, RuleResult, Kind)
  file_naming.go                 Per-rule implementation files (one file per rule)
  directory_structure.go
  repository_placement.go
  handler_placement.go
  file_placement.go
  shared_infra.go
  event_placement.go
  outbox_drain_order.go
  cqrs_pattern.go
  domain_layer_isolation.go
  interface_no_infrastructure.go
  no_cross_aggregate_reference.go
  no_direct_env_access.go
  cross_bc_application_import.go
  no_logging_in_domain.go
  scheduler_in_infrastructure_only.go
  no_silent_catch.go
  dockerfile_conventions.go
  aggregate_id_format.go
  error_response_schema.go
  soft_delete_filter.go
  typed_errors_only.go
  rate_limit_wired.go
  no_generic_response_keys.go
  query_handler_no_raw_aggregate.go
  no_cross_bc_domain_import.go
  import_paths.go                go/parser-based import path extraction helper (shared by several rules)
  text_block.go                  Balanced-bracket/brace extraction helper (skips the interior of string literals, shared by several rules)
  *_test.go                      Per-rule regression tests (standard testing package)
  testdata/<rule>/good/          Minimal fixture that must pass the rule
  testdata/<rule>/bad-*/         Fixture that must fail by violating the rule
```

Each rule function has the signature `func(root string) RuleResult`, and rules are executed and printed in the order they are registered in `main.go`'s `rules` slice.

## Usage

```bash
# From the repository root
bash implementations/go/harness.sh <projectRoot>

# Or directly from the harness/ directory
cd implementations/go/harness
go run . <projectRoot>
```

## Rule list

| Name | File | Role |
|------|------|------|
| `file-naming` | `file_naming.go` | Whether file names are `snake_case.go` |
| `directory-structure` | `directory_structure.go` | Existence of `internal/{domain,application,infrastructure,interface}` + `application/{command,query}` |
| `repository-placement` | `repository_placement.go` | Repository interface -> `domain/`, implementation (`var _ ... = (*Impl)(nil)`) -> `infrastructure/` |
| `repository-naming` | `repository_naming.go` | Whether the method names of `domain/`-layer Repository/Query interfaces follow the `find<Noun>s`/`save<Noun>`/`delete<Noun>` convention (blocklist of `FindBy*`, bare `FindAll`/`Save`/`Delete`, `Count*`, `Update*` — `Update*` is forbidden as a separate update method) — `repository-pattern.md` |
| `handler-placement` | `handler_placement.go` | `*_handler.go` -> `application/command\|query/` or `interface/http/` |
| `file-placement` | `file_placement.go` | `*_task_controller.go` -> `interface/`, `*_scheduler.go` -> `infrastructure/` |
| `shared-infra` | `shared_infra.go` | When `OutboxWriter` is referenced, confirms `outbox/` implements the `Writer`/`Poller`/`Consumer` types; when `*task_queue*` is referenced, confirms placement under `task-queue/` |
| `event-placement` | `event_placement.go` | `*_event_handler.go` -> `application/event/`, `*_integration_event.go` -> `application/integration-event/` |
| `outbox-drain-order` | `outbox_drain_order.go` | Fails if a Command Handler (`*_handler.go`, excluding `*_event_handler.go`) references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls something like `ProcessPending`/`Poll`/`drainOnce` — it must save and return immediately, and Outbox -> SQS publishing/consumption is solely the responsibility of the independently, periodically running Poller/Consumer (synchronous draining is forbidden, domain-events.md) |
| `cqrs-pattern` | `cqrs_pattern.go` | Existence of the `application/command`/`application/query` directories, and whether `application/query/` files reference a write-capable `Repository` type (a port of nestjs's `cqrs-pattern.evaluator.ts`) |
| `domain-layer-isolation` | `domain_layer_isolation.go` | Whether `internal/domain/**/*.go` avoids importing `internal/application\|infrastructure\|interface/` (path-segment based, not a blocklist of specific library names) — `layer-architecture.md` |
| `interface-no-infrastructure` | `interface_no_infrastructure.go` | Whether `internal/interface/**/*.go` (HTTP handlers/routers) avoids directly importing `internal/infrastructure/` — it must depend only on `application/` — `layer-architecture.md` |
| `no-cross-aggregate-reference` | `no_cross_aggregate_reference.go` | Whether the `Payment`/`Refund` Aggregates in `internal/domain/payment/` avoid directly referencing each other as struct fields (only ID string references are allowed) — `domain-service.md` |
| `no-direct-env-access-outside-config` | `no_direct_env_access.go` | Whether `internal/domain/` and `internal/application/` avoid directly calling `os.Getenv`/`os.LookupEnv` — environment variable validation must be consolidated in `internal/config/` only — `config.md` |
| `no-cross-bc-repository-in-application` | `cross_bc_application_import.go` | Whether a single file in `internal/application/` avoids importing `internal/domain/<bc>/` packages from different Bounded Contexts at the same time — other BCs must be accessed only through an `infrastructure/acl` Adapter — `cross-domain-communication.md` |
| `no-logging-in-domain` | `no_logging_in_domain.go` | Whether `internal/domain/**/*.go` avoids importing a logging library such as `log`/`log/slog` — `observability.md` |
| `scheduler-in-infrastructure-only` | `scheduler_in_infrastructure_only.go` | Whether references to `time.Ticker`/`time.NewTicker`/a cron library are absent from `internal/domain/`/`internal/application/` (allowed only in `internal/infrastructure/`) — `scheduling.md` |
| `no-silent-catch` | `no_silent_catch.go` | Whether there is no place that silently swallows an error with a completely empty block like `if err != nil { }` — `observability.md` |
| `dockerfile-conventions` | `dockerfile_conventions.go` | Whether `examples/Dockerfile` is multi-stage (2 or more FROM lines) + has `HEALTHCHECK`, and whether its sibling `.dockerignore` excludes things like `.git`/`.env` — `container.md` |
| `aggregate-id-format` | `aggregate_id_format.go` | Whether `NewID()` in `internal/common/id.go` avoids returning a UUID v4 string as-is without stripping the hyphens (whether there is hyphen-stripping code such as `strings.ReplaceAll(..., "-", "")`, or whether it uses an approach like `hex.EncodeToString` that cannot produce hyphens in the first place) — a single-file check — `aggregate-id.md` |
| `error-response-schema` | `error_response_schema.go` | Finds structs with a `json:"statusCode"` tag (candidate error responses) under `internal/interface/http/**`, and checks whether the json field set is exactly the 4 fields `statusCode`/`code`/`message`/`error` (FAIL if there are more, fewer, or differently named fields) — `error-handling.md` |
| `soft-delete-filter` | `soft_delete_filter.go` | First determines, from `root/migrations/*.sql` (excluding `.down.sql`), which tables have a `deleted_at` column, then checks whether each `Find*`/`FindAll` method in `internal/infrastructure/persistence/*_repository.go` that targets such a table includes a `deleted_at IS NULL` filter (judged by text search, whether in static SQL or a dynamic WHERE-builder seed) — methods targeting a table with no such column are excluded — `persistence.md` |
| `typed-errors-only` | `typed_errors_only.go` | Whether `errors.New(...)` in `internal/domain/`/`internal/application/` is never called outside `domain/<bc>/errors.go` (for application/, any location at all is a violation), and whether `fmt.Errorf(...)` always wraps an existing typed error with `%w` (a free-form message that does not wrap one is a FAIL) — AGENTS.md ("errors must be typed as an enum") |
| `rate-limit-wired` | `rate_limit_wired.go` | Whether a function/method under `internal/interface/http/middleware/` whose name contains `RateLimit` is not dead code — i.e. it is not merely defined but is actually called somewhere at an assembly point such as router.go/main.go — `rate-limiting.md` |
| `no-generic-response-keys` | `no_generic_response_keys.go` | Whether struct declarations under `internal/interface/**` have no field with a `json:"result"`/`json:"data"`/`json:"items"` tag — list response keys must be the plural of the domain object name (`orders`/`accounts`/`payments`) — `api-response.md` |
| `query-handler-no-raw-aggregate` | `query_handler_no_raw_aggregate.go` | Whether the type a `Handle()` method in `internal/application/query/*.go` returns on success is not a pointer type qualified by the `internal/domain/<bc>` package that file imports (e.g. `*account.Account`) — a Query Handler must return only a dedicated Result type — `api-response.md` |
| `no-cross-bc-domain-import` | `no_cross_bc_domain_import.go` | Whether `internal/domain/<bc>/*.go` avoids importing another BC's `internal/domain/<other-bc>` package (generalizes `no-cross-aggregate-reference` within the same BC to the entire BC boundary) — `tactical-ddd.md` |

**Rules not implemented:**

- `no-orm-autosync-in-prod-config` (the ORM `synchronize`/`ddl-auto: update` automatic schema sync forbidden by persistence.md) — Go handles SQL directly via `database/sql` + `lib/pq`, and this repository has no ORM at all. There is no concept corresponding to `synchronize`/`ddl-auto: update` in the first place (as already stated at line 158 of `docs/architecture/persistence.md`), so there is nothing to check — because the schema only ever changes through `migrations/*.sql` files, the principle is upheld structurally instead.
- `aggregate-no-public-setters` — every actual Aggregate in this repository (Account/Card/Payment/Refund/Credential) uses the convention of declaring **all fields exported** (Go has no field-level access control, and this repository chose "public fields + state transitions via domain methods" rather than "private fields + accessor methods"). So the premise of "there are private fields that must be hidden" does not hold in the first place. Catching "whether another package bypasses the domain method and assigns to the field directly" would require type inference to determine whether each assignment expression actually targets an Aggregate-typed variable — and given this repository's style, where field names (`Status`, `Amount`, etc.) are also commonly reused in DTOs/test code, a regex-based approach would have an excessively high false-positive rate, so this rule was not added.

## Regression tests

```bash
cd implementations/go/harness
go test ./...          # per-rule fixture tests
go vet ./...           # static analysis
```

Each rule is verified with at least a `testdata/<rule>/good/` fixture (must pass) and a `testdata/<rule>/bad-*/` fixture (must fail). `testdata/` is a standard directory the Go toolchain ignores automatically, so no extra configuration is needed.

When adding a new rule or modifying an existing one:
1. Implement (or modify) the logic in the corresponding `<rule>.go` file
2. Write `testdata/<rule>/good/` and `testdata/<rule>/bad-*/` fixtures
3. Add a test to `<rule>_test.go` that verifies the fixtures
4. Register it in `main.go`'s `rules` slice (for a new rule)
5. `go test ./... && go vet ./...`
