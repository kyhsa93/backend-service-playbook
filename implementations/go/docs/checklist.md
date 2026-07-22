# AI Agent Self-Review Checklist (Go)

After completing work, review the checklist below in order.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

### Verification rules

- When verifying each STEP, always read the relevant file with the Read tool and cross-check it against the actual code.
- Passing an item without reading the code is forbidden.
- If a violation is found, fix it immediately, then move to the next STEP.
- Items `implementations/go/harness/main.go` automatically verifies (snake_case filenames, existence of the 4-layer directories, placement of Repository/Handler/Scheduler/Task Controller/Event Handler) should also be cross-checked with `./harness.sh <projectRoot>` — however, the harness only checks structure/placement, not semantic rules (method naming, error handling, transactions, etc.), so this checklist supplements it.

---

## STEP 1 — File structure and naming

**Related documents**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] Is there any file whose name isn't in snake_case.go form?
    → If so, rename it to snake_case.go (e.g. get_transactions_handler.go)
[ ] Is the package name a single lowercase word (no underscores/camelCase) matching the directory name?
    → package account, package persistence, etc.
[ ] Does the Domain layer (internal/domain/<domain>/) have an Aggregate Root, Entity, Value Object, Domain Event, sentinel errors, and Repository interface — all present?
    → <aggregate-root>.go, transaction.go (Entity), money.go (VO), events.go, errors.go, repository.go
[ ] Is a status-like value object split into its own file (<domain>_status.go)?
    → like account_status.go — don't cram multiple concepts into one domain file
[ ] Is the Application layer filename in <verb>_<noun>_handler.go form, placed under command/ or query/?
    → create_account_handler.go, get_transactions_handler.go
[ ] Are Result DTOs gathered in application/query/result.go (or a similar file)?
[ ] Is a Technical Service interface (notification, secret, storage, etc.) defined in the application package that uses it?
    → command.AccountAdapter, command.PasswordHasher, command.SecretService, command.StorageService
[ ] Is an Infrastructure layer file placed as internal/infrastructure/<concern>/<aggregate>_repository.go?
    → persistence/account_repository.go, notification/service.go
[ ] Is an Interface layer file placed as internal/interface/http/<domain>_handler.go, dto.go, router.go?
[ ] If the number of domains has grown, was subdividing into application/command/<domain>/, infrastructure/persistence/<domain>_repository.go, etc. considered?
    → with only a single domain, a flat structure is fine as-is (YAGNI)
[ ] Is shared code (ID generation, etc.) in a framework-agnostic pure-function-only package like internal/common/?
    → has domain-specific code been kept out of the shared package?
[ ] Are type names PascalCase, public functions/methods PascalCase, and private functions/methods camelCase?
[ ] Are error variable names in ErrXxx form? (ErrNotFound, ErrInsufficientBalance, etc.)
[ ] Do interface names prefer role nouns over verb+er? (Repository, AccountAdapter — avoid Fetcher/Sender-style names)
[ ] Does the Repository implementation have compile-time interface verification (`var _ <domain>.Repository = (*XRepository)(nil)`)?
```

---

## STEP 2 — Domain layer

**Related documents**: [architecture/tactical-ddd.md](architecture/tactical-ddd.md) · [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/aggregate-id.md](architecture/aggregate-id.md) · [domain-service.md](../../../docs/architecture/domain-service.md) (shared root document)

```
[ ] Does the Domain package import only standard-library base packages plus a minimal dependency (google/uuid, etc.)?
    → confirm there's no framework/infrastructure import such as log, net/http, context, os, database/sql
[ ] Are invariants validated only inside Aggregate methods?
    → confirm a Handler or another package isn't directly assigning a field to change state
[ ] Are fields that must never be exposed outside the Aggregate (an event slice, a child Entity slice, etc.) declared unexported (lowercase)?
    → events, transactions, etc.
[ ] Is access to events/child Entities done only through explicit public methods like DomainEvents()/ClearEvents()?
[ ] Are new creation (`New(...)`) and DB restoration (`Reconstitute(...)`) split into separate functions?
    → New() raises events and issues a new ID; Reconstitute() only fills in state, with no events
[ ] Does New(...)'s parameter list have no ID? (a client-supplied ID is never accepted)
[ ] Is the Aggregate ID generated as a 32-character hex string, a UUID v4 with hyphens removed (`common.NewID()`)?
    → is `uuid.NewString()` being used as-is with hyphens included (a known gap in this repository's examples/ — don't reproduce it in newly written code)
[ ] Do Value Object methods use a value receiver (`(m Money)`) and return a new value instead of mutating the original?
[ ] Does the Value Object have an attribute-based equality comparison method (`Equals(other T) bool`)?
[ ] Are Domain Event type names past-tense (AccountCreated, MoneyDeposited)?
[ ] Do Domain Events share a common marker interface (e.g. `isAccountDomainEvent()`) to prevent an arbitrary type from being mixed in accidentally?
[ ] Is the Repository interface defined with only a signature in the domain package, with no implementation?
[ ] Do references between Aggregates use only ID references, with no other Aggregate object held directly as a field?
[ ] Are multiple Aggregates never mixed into the same package? (one package per Aggregate is the principle)
[ ] Does code within the same package never manipulate an Aggregate field directly without going through a method?
    → since Go only supports package-level encapsulation, this discipline must be supplemented via code review
```

---

## STEP 3 — Layer architecture / CQRS / cross-domain

**Related documents**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](architecture/cqrs-pattern.md) · [architecture/cross-domain.md](architecture/cross-domain.md) · [architecture/domain-events.md](architecture/domain-events.md)

```
[ ] Is the dependency direction Interface → Application → Domain, with Infrastructure implementing Domain's interface?
    → confirm the domain package's import list has no infrastructure/interface/application
[ ] Does the CommandHandler/QueryHandler follow the struct + `Handle(ctx context.Context, cmd/query X) (result, error)` signature?
[ ] Does the Handler perform business logic directly (checking state-transition conditions, calculating amounts, etc.)?
    → if so, move it into an Aggregate domain method
[ ] Does the Handler only orchestrate, in the order (1) Repository lookup → (2) call a domain method → (3) Repository save → (4) side effects (notification, etc.)?
[ ] Does the Handler return the error as-is, or wrap it with `fmt.Errorf("...: %w", err)`? (it never panics instead)
[ ] If the Command reuses the same Repository as Query without a separate Query interface, has this been recognized as a known deviation in this repository?
    → once the read model needs to be split into a separate store, create a new Query interface in `application/query/` + a read-only implementation in `infrastructure/`
[ ] Is a new runtime routing layer such as CommandBus/QueryBus being introduced?
    → the Handler instance should be held directly as a field and called (no Bus needed — the type is already fixed at compile time)
[ ] Is another domain's Repository/Service referenced directly from the Application layer?
    → if so, switch to the Adapter pattern: an interface in the calling side's application package, an implementation in the calling side's infrastructure package
[ ] Does the Adapter interface declare only the minimal shape the calling side needs, rather than the callee's full API?
[ ] Does the Adapter implementation also have `var _ Interface = (*Impl)(nil)` compile-time verification?
[ ] Are domain events created only inside Aggregate domain methods?
    → confirm the Handler isn't directly constructing an event struct and appending it
[ ] If a new domain's Handler sends a notification via a separate synchronous call right after a Repository save, with no Outbox, has this been recognized as a dual-write risk?
    → for a side feature that's fine to fail (email notification, etc.), the current pattern may be a practical trade-off. For an important event like a legal notice, consider introducing the Outbox (recording the event in the same transaction as the Repository save) + `outbox.Poller`/`outbox.Consumer` (asynchronous publish/receive) pattern (domain-events.md)
[ ] Is there a package circular dependency (import cycle)?
    → Go has no workaround like `forwardRef()`. Extract the shared concept into a third package, force one direction via an Adapter, or consider switching to asynchronous (Integration Event)
```

---

## STEP 4 — Repository pattern

**Related documents**: [architecture/repository-pattern.md](architecture/repository-pattern.md) · [architecture/persistence.md](architecture/persistence.md)

```
[ ] Is the Repository defined per Aggregate Root? (not per table/Entity)
[ ] Is the Repository interface in the domain package, with the implementation in the infrastructure package?
[ ] Does the implementation have `var _ <domain>.Repository = (*XRepository)(nil)` compile-time verification?
[ ] Does the Repository method follow the find<Noun>s (single item unified via take:1)/save<Noun>/delete<Noun> (/a separate state-transition method if needed) pattern?
    → never add a single-item-only lookup method (FindByID, etc.) — always wrap a find<Noun>s call with take:1 in a FindOne helper (the same across account/card/payment)
[ ] Is there an update<Noun>-style method?
    → if so, remove it. Change state via an Aggregate domain method after lookup, apply it via Save
[ ] Does the Repository implementation convert a DB row into the domain Aggregate? (uses `Reconstitute(...)`, doesn't return the row as-is)
[ ] Does the dynamic WHERE condition follow the pattern of appending to a slice only when a value is present (not the zero value)?
    → does the `$N` placeholder number increment correctly with each added condition, and are values always bound via args instead of being inserted directly into the string (preventing SQL injection)?
[ ] Does a list-query method return in the form (a slice of domain objects, count, error)?
[ ] Is the soft-delete column (deleted_at) included in the query's WHERE by default? (`deleted_at IS NULL`)
[ ] Is soft delete (UPDATE ... SET deleted_at) used instead of hard delete (`DELETE FROM ...`)?
[ ] Is there a Command that needs to bundle multiple Repositories into one transaction?
    → this repository currently has no `context.Context`-based transaction propagation (`database.WithTx`) implemented (a known gap). Introduce this pattern once a transaction spanning more than a single Repository becomes necessary
[ ] Are migrations managed as sequentially numbered SQL files (`migrations/000X_*.sql`)? (automatic schema sync is forbidden — `database/sql` has no auto-sync feature to begin with)
```

---

## STEP 5 — Dependency assembly (no DI container)

**Related documents**: [architecture/module-pattern.md](architecture/module-pattern.md) · [architecture/shared-modules.md](architecture/shared-modules.md) · [architecture/bootstrap.md](architecture/bootstrap.md)

```
[ ] Is a new Infrastructure implementation created via a `New...()` constructor function?
[ ] Is the Application Handler constructor injected with the dependency as the domain package's interface type, not a concrete type?
    → `repo account.Repository`, not `repo *persistence.AccountRepository`
[ ] Does main.go's (or router.go's) assembly order exactly match the dependency direction? (DB → Infrastructure → Application → Interface → server start)
[ ] Does main() contain business logic or conditional branching beyond wiring?
    → if so, remove it. main() is only responsible for calling constructors in order and starting the server
[ ] Is a shared instance (DB connection pool, logger, etc.) passed as the same value to multiple domains' constructors?
    → "sharing" is achieved by passing the same variable as an argument to multiple constructors, not by a NestJS-style `@Global()` declaration
[ ] Has domain-specific code been mistakenly moved into a shared package like `internal/common/`?
    → create a shared package only once two or more domains actually need it (YAGNI)
[ ] Is there a package circular dependency?
    → Go has no workaround. Immediately redesign via extracting a shared concept, unidirectionalizing with an Adapter, or switching to asynchronous
[ ] When a call to another domain is needed, is the Adapter interface in the calling side's application package, with the implementation in the calling side's infrastructure package?
[ ] If `application/command/`, `application/query/` remain flat despite more domains having appeared, has it been considered whether it's time to subdivide into `command/<domain>/`, `query/<domain>/`?
[ ] Has a reflection-based DI container or service locator not been implemented directly? (in Go, constructor chaining is accepted as-is)
```

---

## STEP 6 — Go typing and domain modeling patterns

**Related documents**: [conventions.md](./conventions.md)

```
[ ] Do all functions/methods that can fail explicitly declare `error` as a return value?
[ ] Is `context.Context` the first argument of every function crossing a layer boundary? (Repository, Handler, Adapter, Technical Service — all of them)
[ ] Are DTO/Result/Command/Query struct fields declared exported (PascalCase)?
[ ] Is `any` (interface{}) used anywhere without good reason?
    → if so, replace it with a concrete type or a well-defined interface
[ ] Has it been deliberately decided whether a zero-value idiom (`""`, `0`, `nil`) is sufficient for expressing a nullable value, or whether an explicit null via a pointer (`*string`, etc.) is needed?
[ ] Do Value Object methods use a value receiver and never mutate the original?
[ ] Do Aggregate methods use a pointer receiver (`(a *Account)`) to change state?
[ ] Has a named struct/type been defined for a complex return type to improve readability? (no overuse of anonymous structs)
[ ] Is an enum-like value (Status, etc.) defined as `type Status string` + a constant group? (no direct magic-string comparisons)
```

---

## STEP 7 — Error handling

**Related documents**: [architecture/error-handling.md](architecture/error-handling.md)

```
[ ] Is a new error defined as a sentinel error (`var ErrXxx = errors.New("...")`)?
[ ] Does the error message start lowercase with no trailing punctuation? (standard Go convention)
[ ] When an upper layer wraps an error, is it wrapped with `fmt.Errorf("<operation description>: %w", err)` to preserve the original?
[ ] Is an already-sentinel error being unnecessarily rewrapped? (this only lengthens the errors.Is chain without adding information)
[ ] Is HTTP status code mapping via `errors.Is` done only in the Interface layer (HTTP Handler)?
    → confirm the Domain/Application layers know nothing about `net/http` status codes or HTTP concepts
[ ] Is an error not present in the mapping (the default branch) handled as a 500 + a generic message that doesn't expose internal implementation details to the client?
[ ] If the standard error response JSON schema (`{statusCode, code, message, error}`) is needed for the work at hand, has it been introduced?
    → this repository currently has a known gap of returning only plain text via `http.Error`. If there's a requirement for the client to branch on `code`, switch to the JSON schema
[ ] Is `panic` never used anywhere — Aggregate/Repository/Handler — to express an ordinary failure? (returning `error` is the principle)
[ ] If a new sentinel error was added, was it gathered together in that domain's `errors.go`?
```

---

## STEP 8 — REST API endpoints

**Related documents**: [architecture/api-response.md](architecture/api-response.md) · [conventions.md](./conventions.md) section 5 · [conventions.md](../../../docs/conventions.md) (shared root document) section 1

```
[ ] Is the URL made up of plural noun resources, not verbs?
    → correct example: GET /accounts, POST /accounts
    → incorrect example: GET /getAccounts, POST /createAccount
[ ] Are non-CRUD actions expressed as sub-resource paths?
    → correct example: POST /accounts/{id}/deposit, POST /accounts/{id}/suspend
[ ] Are the HTTP method and response code correct? (GET/PUT/PATCH 200, POST 201, DELETE 204)
[ ] Is routing registered using `net/http`'s method+path pattern (`mux.HandleFunc("GET /accounts/{id}", ...)`, Go 1.22+)?
[ ] Is pagination parsed from the `page` (0-based)/`take` query parameters?
    → parsed directly from `r.URL.Query()` — has it been deliberately decided whether a parse failure is absorbed into a default value or turned into a 400?
[ ] Is a list response made up of a domain-plural key (`accounts`, `transactions`, etc.) + `count`, instead of a generic key (`data`/`result`/`items`)?
[ ] Is `count` the total count after filters are applied, not the page size?
[ ] Does a single-item response expose fields flat, with no generic wrapper like `{ success, data }`?
[ ] Does the Result struct (Application layer) map onto response-only fields instead of exposing the domain Aggregate as-is?
[ ] Is the Interface DTO a thin wrapper that redeclares the Application Result/Command's fields, with the Handler mapping fields explicitly?
    → since Go has no inheritance, TypeScript's `extends` is replaced with field redeclaration + explicit mapping
```

---

## STEP 9 — Authentication, cross-cutting concerns, rate limiting

**Related documents**: [architecture/authentication.md](architecture/authentication.md) · [architecture/cross-cutting-concerns.md](architecture/cross-cutting-concerns.md) · [architecture/rate-limiting.md](architecture/rate-limiting.md) · [architecture/observability.md](architecture/observability.md)

```
[ ] Is authentication validation done only in the Interface layer (middleware)?
    → confirm the Application/Domain packages don't import any `jwt`-related package
[ ] Does the authentication middleware extract and validate the `Authorization: Bearer` header, then pass user info to the next handler via `context.WithValue`?
[ ] Does the JWT payload carry only minimal information such as `userId`? (role/email or other sensitive/mutable info is forbidden)
[ ] Are routes requiring authentication wrapped with middleware at the group (sub-mux) level?
    → confirm this isn't wrapped separately per individual handler, creating a risk of omission when adding a new endpoint
[ ] Is an unvalidated header (`X-User-Id`, etc.) being mistaken for authentication and trusted as-is?
    → this is a known gap in this repository's `examples/`. For newly written features, has it been implemented with JWT-based middleware?
[ ] Is the middleware chain split one-per-concern? (is the order Correlation ID → Auth → Rate Limit → Handler → Logging reasonable?)
[ ] Is a `golang.org/x/time/rate`-based token bucket applied to endpoints that need rate limiting?
[ ] If per-client limiting is needed, does the limiter map have cleanup logic (removing stale entries)? (without it, it's a memory leak)
[ ] Are write endpoints limited more strictly than reads?
[ ] Are healthcheck/internal endpoints excluded from the authentication/rate-limit middleware?
[ ] Does the Domain layer avoid importing cross-cutting concerns such as logger/HTTP/authentication?
```

---

## STEP 10 — Import organization

**Related documents**: [conventions.md](./conventions.md) section 7

```
[ ] Are imports sorted into 3 groups — standard library → third-party → internal packages (with a blank line between groups)?
[ ] Is each group sorted alphabetically internally? (does it match `goimports`'s output?)
[ ] Is an internal package alias (`httphandler "internal/interface/http"`, etc.) used reasonably, only to avoid a name collision?
[ ] Is a blank import (`_ "github.com/lib/pq"`, etc., for driver registration) in the correct position within the third-party group?
[ ] Does a Domain layer file avoid importing the infrastructure/interface/application packages?
[ ] Do `gofmt -l .` and `goimports -l .` return empty output? (no formatting violations)
```

---

## STEP 11 — Scheduling, Task Queue, graceful shutdown

**Related documents**: [architecture/scheduling.md](architecture/scheduling.md) · [architecture/graceful-shutdown.md](architecture/graceful-shutdown.md) · [architecture/domain-events.md](architecture/domain-events.md) · [architecture/container.md](architecture/container.md)

```
[ ] Is the Scheduler (`*_scheduler.go`) located in the infrastructure layer (`internal/infrastructure/scheduling/`, etc.)?
[ ] Does the Scheduler only call `TaskQueue.Enqueue` without directly running business logic?
[ ] Is the Scheduler's `time.Ticker` loop terminated via `ctx.Done()`? (tied to graceful shutdown)
[ ] Is an error from the Ticker callback never ignored, and instead explicitly logged via `slog.ErrorContext`, etc.?
    → exceptions in a Cron/Ticker callback are easily swallowed silently, so always log them explicitly
[ ] Is Task enqueueing done inside the same transaction as the DB change? (preventing dual-write)
[ ] Does the Task Consumer return an error as-is instead of swallowing it, deferring to the retry/DLQ mechanism?
[ ] Is the event/Task handler implemented idempotently? (has it been judged which tier is needed among intrinsic idempotency / a processing-record table / strong atomicity?)
[ ] Is the shutdown signal converted into context cancellation via `signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)`?
[ ] On receiving SIGTERM, is readiness flipped to 503 first, before `srv.Shutdown(ctx)` is called? (is the order not reversed?)
[ ] Is the shutdown timeout matched with the orchestrator's `terminationGracePeriodSeconds`?
[ ] Is the container's `ENTRYPOINT`/`CMD` in exec form (`["/bin/server"]`) so SIGTERM isn't intercepted by a shell?
```

---

## STEP 12 — DB / infrastructure patterns

**Related documents**: [architecture/persistence.md](architecture/persistence.md) · [architecture/config.md](architecture/config.md) · [architecture/secret-manager.md](architecture/secret-manager.md) · [architecture/local-dev.md](architecture/local-dev.md) · [architecture/container.md](architecture/container.md) · [architecture/observability.md](architecture/observability.md)

```
[ ] Are required environment variables validated at startup, leading to `log.Fatal` (immediate exit) on failure?
    → is `os.Getenv` being passed straight into `sql.Open`, etc. without validation?
[ ] Are environment variables split into per-concern Config structs (`Load...Config()`)?
[ ] Is a local-development-only default value (`getEnvOr`) distinguished from a value that must be present in production (an error if the string is empty)?
[ ] Are sensitive values (DB password, JWT secret, API key) never hardcoded?
    → are they looked up via something like AWS Secrets Manager in production?
[ ] Is the SecretService lookup result cached via a TTL cache (`sync.Mutex` + map) to avoid repeated lookups?
[ ] Do the Domain/Application packages have no `os.Getenv` calls? (config access is Infrastructure/`main.go` only)
[ ] Are the `created_at`/`updated_at`/`deleted_at` columns present in the schema, and does the query filter with `deleted_at IS NULL`?
[ ] Are migrations managed as sequentially numbered SQL files? (are known gaps such as the absence of down scripts acknowledged?)
[ ] Are structured logs written via `log/slog`? (a JSON handler, snake_case field names, using `InfoContext`/`ErrorContext`, which take a `ctx`)
[ ] Does the correlation ID propagate via `context.Context` and get included in every log?
[ ] Is the container image built with a multi-stage build (`CGO_ENABLED=0` static build + `distroless`/`scratch`)?
[ ] Is `ENTRYPOINT` in exec form, and does the image avoid baking in environment variables (`ENV DATABASE_URL=...`, sensitive values, etc.)?
[ ] Are the healthcheck endpoints (`/health/live`, `/health/ready`) implemented directly with `net/http`?
[ ] Is local infrastructure such as LocalStack defined in `docker-compose.yml` with a version-pinned image (`:latest` is forbidden)?
```

---

## STEP 13 — Testing patterns

**Related documents**: [architecture/testing.md](architecture/testing.md)

```
[ ] Are Domain unit tests written as `package <domain>_test` (an external test package), using only the public API?
[ ] Does it follow the table-driven test style (`[]struct{...}` + `t.Run` subtests)?
[ ] Is the expected error compared with `errors.Is`? (string comparison is forbidden)
[ ] Do Application unit tests replace an interface such as Repository with a manual stub struct? (mocking a concrete type is forbidden)
[ ] Does the Application test verify only the Handler's call order (lookup → domain method → save → notify), without re-verifying business logic?
[ ] Do E2E tests start real Postgres/LocalStack containers via testcontainers-go? (connecting directly to a production DB is forbidden)
[ ] Does the E2E test start the container once in `TestMain` and run the migration files in order?
[ ] Does each test generate a unique ID/owner to keep test data isolated?
[ ] Are unit tests placed next to the source (`_test.go`), and E2E tests in a separate `test/` directory?
[ ] Does the test function name follow the `TestXxx_When<condition>_Then<expected result>` pattern?
    → e.g. `TestDepositHandler_Handle_AccountNotFound`, `TestAccount_Withdraw` (with the condition/expected result stated in the subtest name)
[ ] Are there tests for Aggregate invariant violations and for whether a Domain Event was raised?
```

---

## STEP 14 — Final overall consistency check

**Related documents**: [conventions.md](./conventions.md) · [architecture/design-principles.md](architecture/design-principles.md)

```
[ ] Was a newly added file assembled via a constructor wherever it's needed (main.go/router.go)?
[ ] If a new Command/Query/Result was added, was the Interface DTO added together as a thin mapping struct?
[ ] Does the code worked on have no leftover TODOs, temporary `fmt.Println`/debug output, or temporary comments?
[ ] Is the ubiquitous language consistently reflected in type names/method names/field names?
[ ] Does every exported identifier have a Go doc comment (a `//` comment starting with the identifier's name)?
[ ] Is business logic explained via inline `//` comments? (no overuse of block comments)
[ ] Are log fields structured (snake_case) and do they include the correlation ID?
[ ] Does it pass `gofmt`/`goimports`/`go vet`?
[ ] Does the commit message follow the Conventional Commits format (feat/fix/refactor + scope)?
[ ] Is the commit message's scope the service domain name (account, user, etc.)?
[ ] Is the commit message's description written in descriptive form, with no trailing period?
    → correct example: `feat(account): add account suspend feature`
[ ] Does the commit message's body explain "why" the change was made?
[ ] If there's a BREAKING CHANGE, is it noted in the footer or with a `!` after the type?
[ ] Is the branch name in Conventional Branch format (`<type>/<scope>-<description>`), kebab-case, and branched from main?
[ ] Is work never committed/pushed directly to the main branch, and always merged via a PR?
[ ] Is the PR title identical in form to Conventional Commits, and does the body follow the Summary + Test plan format?
[ ] Is the merge strategy Squash and merge?
[ ] Does the test naming follow the `TestXxx_When_Then` pattern?
[ ] Do both `go build ./...` and `go test ./...` pass?
[ ] Does `./harness.sh <projectRoot>` pass with no FAILs?
```

---

## STEP 15 — Design artifact format (for design-phase work)

**Related documents**: [development-process.md](../../../docs/development-process.md) (shared root document) · [reference.md](./reference.md)

> Applies only when a design-phase (RA, SD, DM, TD) artifact was produced. Since the artifact format at this stage isn't language-dependent, the root document is followed as-is.

```
[ ] RA artifact: do functional requirements include an FR-### number, description, acceptance criteria, and priority (MoSCoW)?
[ ] RA artifact: does a use case include a UC-### number, Actor, preconditions, the main flow (Happy Path), exception flows, and postconditions?
[ ] RA artifact: does the constraint summary table include tech stack, external systems, schedule, regulations, and traffic items?
[ ] SD artifact: does the subdomain classification table include the type (Core/Supporting/Generic) and the implementation strategy?
[ ] SD artifact: does the Bounded Context definition sheet include responsibilities, key concepts, and its parent subdomain?
[ ] SD artifact: does the Context Map include the relationship type (Partnership/Shared Kernel/Customer-Supplier/Conformist/ACL/OHS·PL) and the reason it was chosen?
[ ] DM artifact: does the event-storming result mapping table include Actor/Command/Aggregate/Domain Event/Policy/External System columns?
[ ] DM artifact: does the ubiquitous language glossary include term (English)/term (Korean)/definition/parent Context/notes columns?
[ ] DM artifact: is it noted in the glossary when the same word carries a different meaning in different Contexts?
[ ] DM artifact: does the per-Aggregate domain model structure include the Root/Entity list/VO list/relationships?
[ ] DM artifact: does the detailed Domain Event list include event name/trigger condition/included data/follow-up processing (Policy) columns?
[ ] DM artifact: do business rules/invariants include an INV-### number and the handling on violation?
[ ] TD artifact: does the file structure tree include the 4 layers `internal/{domain,application,infrastructure,interface}`?
    → unlike NestJS, in Go the layer is at the top level and the domain sits underneath it (see [directory-structure.md](architecture/directory-structure.md))
[ ] TD artifact: does the dependency-assembly plan specify which constructor is called where (main.go/router.go)? (since there's no DI container, this is expressed as constructor call order instead of provide/useClass)
[ ] TD artifact: does the Aggregate design sheet include the Root/internal Entities/internal VOs/external references (ID)/creation rules/invariants?
[ ] TD artifact: does the Repository interface definition sheet include find<Noun>s/save<Noun> (/a state-transition method if needed) signatures? (a single-item-only lookup method is never included)
[ ] TD artifact: does the Application Handler definition sheet include the use-case mapping/processing flow/transaction scope/failure handling?
[ ] TD artifact: does the Event flow diagram include the synchronous/asynchronous processing approach and compensating transactions?
[ ] IM artifact: is the work proceeding via Vertical Slicing (implementation per use case)?
    → implement every layer at once per use case (vertically), not per layer (horizontally)
[ ] IM artifact: is the slice plan organized in slice-number/use-case/included-files/priority format?
```

---

## STEP 16 — When modifying the guide itself

**Related documents**: [development-process.md](../../../docs/development-process.md) (shared root document) · [conventions.md](./conventions.md)

> Applies only when modifying the guide itself (`implementations/go/docs/**`), not code.

```
[ ] Is the newly added or modified description written in Korean?
[ ] Does a new rule come with both a correct example and an incorrect example?
[ ] Does the written example not contradict actual Go idioms (returning errors, propagating context.Context, compile-time interface verification, etc.)?
[ ] Does the document clearly distinguish between "the target implementation" and "the actual code in this repository's examples/"?
    → several of this repository's architecture documents deliberately leave known gaps in place (UUID hyphens, etc.). When writing a new document, don't conflate the target state with the current state
[ ] Does the written example avoid violating another rule in this guide (file naming, imports, error handling, etc.)?
    → if there's a violation, fix the example first, then finalize the rule
[ ] Is a guide change made via a PR from a new branch, not the main branch?
```

---

## How to use this checklist

After completing work, the AI Agent performs self-review in the following order:

1. Check **STEP 1 through 14 in order**.
2. When a violation is found, **fix the file immediately** and check it off.
3. After fixing, confirm **there's no impact on related files** as well (constructor assembly in main.go/router.go, etc.).
4. If the work was design-phase work, also check **STEP 15**.
5. If the work was a guide modification, also check **STEP 16**.
6. After completing every check, cross-check the structure/placement rules with `./harness.sh <projectRoot>` and wrap up the work.

> The checklist is a summary of the guide's rules.
> If an item's intent is unclear, consult the relevant document:
> - STEP 1 File structure and naming → [conventions.md](conventions.md) sections 1-3, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 Domain layer → [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 Layer architecture / CQRS / cross-domain → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [cross-domain.md](architecture/cross-domain.md), [domain-events.md](architecture/domain-events.md)
> - STEP 4 Repository pattern → [repository-pattern.md](architecture/repository-pattern.md), [persistence.md](architecture/persistence.md)
> - STEP 5 Dependency assembly → [module-pattern.md](architecture/module-pattern.md), [bootstrap.md](architecture/bootstrap.md)
> - STEP 6 Go typing → [conventions.md](conventions.md) section 4
> - STEP 7 Error handling → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API endpoints → [conventions.md](conventions.md) section 5, [api-response.md](architecture/api-response.md)
> - STEP 9 Authentication, cross-cutting concerns, rate limiting → [authentication.md](architecture/authentication.md), [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md), [rate-limiting.md](architecture/rate-limiting.md)
> - STEP 10 Imports → [conventions.md](conventions.md) section 7
> - STEP 11 Scheduling / Task Queue / graceful shutdown → [scheduling.md](architecture/scheduling.md), [graceful-shutdown.md](architecture/graceful-shutdown.md)
> - STEP 12 DB/infrastructure → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [observability.md](architecture/observability.md)
> - STEP 13 Testing patterns → [testing.md](architecture/testing.md)
> - STEP 14 Overall consistency → refer to all documents
> - STEP 15 Design artifact format → [development-process.md](../../../docs/development-process.md) (shared root document)
> - STEP 16 Guide modification → [CLAUDE.md](../CLAUDE.md) guide-management principles
