# AI Agent Self-Review Checklist

After finishing a task, review the checklist below in order.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

### Verification rules

- When verifying each STEP, always read the file in question with the Read tool and compare it against the actual code.
- Passing an item without reading the code is forbidden.
- If a violation is found, fix it immediately, then move to the next STEP.
- Where possible, run `harness/harness.py <projectRoot>` first to mechanically filter out structural-rule violations (file naming, layer placement, domain purity, layer-dependency) — the harness only automatically checks part of this checklist, so verify the rest directly against this document.

---

## STEP 1 — File structure and naming

**Related documents**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] Is there any file name that isn't snake_case.py?
    → If so, rename it to snake_case.py (the same criterion as the harness's file-naming check)
[ ] Is the Handler file named <verb>_<noun>_handler.py and located under application/command/ or application/query/?
    → If not, move it (the same criterion as the harness's handler-placement check)
[ ] Is a DTO (Pydantic request/response) class name verb-first instead of noun-first?
    → Correct example: CreateOrderRequest, GetOrderResponse
[ ] Is a domain enum declared inline somewhere other than the domain/ layer?
    → If so, split it out into domain/<concern>_status.py
[ ] Are constants scattered across multiple files as magic numbers/strings?
    → Split them out into constants.py at the domain package root
[ ] Is the Router file named <domain>_router.py?
    → It should live under interface/rest/; rename it if it doesn't
[ ] Does the Domain layer's file naming follow the convention?
    → Aggregate Root: <aggregate_root>.py, Value Object/Entity: <name>.py, Domain Event: events.py, exceptions: errors.py
[ ] Is the Repository interface in the domain/ layer and defined as an ABC? (repository.py or <aggregate>_repository.py)
[ ] Is the Repository implementation in infrastructure/persistence/, with the implementation technology as a class-name prefix (SqlAlchemy, etc.)?
[ ] Is the Query Result defined as a dataclass in application/query/result.py?
[ ] Does the Adapter file naming follow the convention?
    → Interface: <external_domain>_adapter.py (application/adapter/)
    → Implementation: <provider>_<external_domain>_adapter.py (infrastructure/)
[ ] Does the technical infrastructure Service (Technical Service) file naming follow the convention?
    → Interface: <concern>_service.py (application/service/)
    → Implementation: <provider>_<concern>_service.py (infrastructure/<concern>/)
[ ] Is the configuration class file named <concern>_config.py and located under the config/ directory?
[ ] Is the configuration-validation file named validator.py and located under the config/ directory?
[ ] Does the class naming follow the convention?
    → Aggregate Root: a domain noun (Order, Account)
    → Value Object/Entity: a domain concept (Money, OrderItem)
    → Domain Event: past tense (OrderPlaced, OrderCancelled)
    → Repository interface: <Aggregate>Repository / implementation: SqlAlchemy<Aggregate>Repository
    → Adapter interface: <ExternalDomain>Adapter / implementation: <Provider><ExternalDomain>Adapter
    → Command: <Verb><Noun>Command / Query: <Verb><Noun>Query / Result: <Verb><Noun>Result
    → CommandHandler/QueryHandler: <Verb><Noun>Handler
    → Domain exception: <Domain concept><Situation>Error / error-code enum: <Domain>ErrorCode
```

---

## STEP 2 — The Domain layer

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [domain-service.md](../../../docs/architecture/domain-service.md) (root, shared) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] Does the domain/ directory have the Aggregate Root, Entity, Value Object, Domain Event, and Repository interface?
[ ] Are business rules and invariants encapsulated in the Aggregate Root?
    → If an Application Handler has business logic, move it into the Aggregate
[ ] Is the Aggregate Root declared as a @dataclass?
    → If so, change it to a plain class (__init__ + methods). A dataclass's auto-generated constructor doesn't suit protecting invariants
[ ] Are the Entity/Value Object/Domain Event declared as @dataclass(frozen=True)?
[ ] Does a Domain-layer file import an external library such as fastapi/sqlalchemy/aioboto3?
    → If so, remove it. The Domain layer uses only the standard library (dataclasses, abc, datetime, enum) (the same criterion as the harness's domain-purity check)
[ ] Is the Repository interface defined as an ABC + @abstractmethod?
[ ] Is the Repository interface located in the domain/ layer? (the same criterion as the harness's repository-abc check)
[ ] Do Aggregates reference each other only by ID, with no direct reference?
[ ] Is there code outside the Aggregate that directly mutates its internal state?
    → If so, fix it to change state only through the Aggregate Root's methods
[ ] Does a Value Object just use the frozen dataclass's auto-generated __eq__ (attribute-based equality) as-is?
    → Never create a separate equals() method
[ ] Is the ID generated as uuid.uuid4().hex (hyphens removed, a 32-character hex string) when an Aggregate is created?
    → If str(uuid.uuid4()) (hyphens included) is used, replace it with .hex
[ ] Does ID generation happen inside the Aggregate's factory classmethod (Order.create(), etc.)?
    → When restoring from the DB (the Repository's _to_domain()), does it use the existing value as-is instead of generating a new ID?
[ ] Does the Domain layer avoid using logging (logging, contextvars, etc.)?
    → The Domain layer is agnostic not just to frameworks but to cross-cutting concerns too
```

---

## STEP 3 — Layer architecture / CQRS / events

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Does a route function perform any logic beyond constructing the Handler + calling execute() + converting the response?
    → If so, move it into the Handler
[ ] Does a Handler perform business logic directly? (checking a state-change condition, a computation, etc.)
    → If so, move it into the Aggregate's domain method
[ ] Does a Handler raise an HTTP-related exception such as HTTPException?
    → If so, replace it with a plain Exception subclass from domain/errors.py
[ ] Does a Handler use a SQLAlchemy session/query directly?
    → If so, move it into the Repository implementation
[ ] Does a Repository implementation contain business logic?
    → If so, move it into the Aggregate or the Handler
[ ] Is a Handler class's member order (1) __init__ → (2) execute() → (3) private helpers?
[ ] Does the Application layer's directory structure match directory-structure.md? (command/, query/, etc.)
    → If there's a write use case, a command/ directory and a Command dataclass + Handler must exist
    → If there's a read use case, a query/ directory and a Query dataclass + Result + Handler must exist
[ ] Are the Command Handler and Query Handler physically separated? (application/command/ vs. application/query/, the same criterion as the harness's handler-placement check)
[ ] Does a Query Handler return the domain Aggregate directly?
    → If so, convert it into a Result dataclass in application/query/result.py before returning
[ ] Does a route function (an Interface DTO) have logic or fields beyond Pydantic validation?
    → If so, move it into an Application Command/Query/Result
[ ] Is the layer dependency direction correct? (interface → application → domain ← infrastructure)
    → If application/ directly imports a concrete class from infrastructure/, fix it (the same criterion as the harness's layer-dependency check)
[ ] Are all route functions async def, with an explicit return type (the same type as response_model)?
[ ] Is a child Entity inside an Aggregate saved/looked up together through the Aggregate Root's Repository?
    → Never create a separate Repository for a child Entity
[ ] Are events created only inside a domain method within the Aggregate? (self._events.append(...))
    → A Handler never creates an event object directly
[ ] Does the Repository implementation's save method save, in the same transaction, the events the Aggregate pulled via pull_events() into the Outbox table?
    → If a Handler directly calls a Technical Service (NotificationService, etc.) to process an event, consider switching to go through the Outbox (see "known gaps" in domain-events.md)
[ ] Does the Repository implementation clear the buffer via aggregate.pull_events() after saving events to the Outbox? (prevents duplicate saves)
[ ] Does Outbox polling (`OutboxPoller`) avoid marking a row processed (processed=True) if publishing to SQS fails, so it's retried on the next tick? (at-least-once)
[ ] Is the follow-up processing of an event (sending a notification, etc.) implemented idempotently?
    → Check via a Ledger table whether the event was already processed, or prevent duplication via a DB unique constraint
[ ] When a cross-domain call is needed, is an ABC defined in the calling side's application/adapter/, with the implementation in infrastructure/?
    → If an Application Handler directly imports another domain's Repository/Handler, replace it with an Adapter
[ ] Does the Adapter interface define only the methods the calling side needs, without exposing the target domain's entire API?
[ ] Does the Adapter convert the target domain's internal model into a DTO (dataclass) dedicated to the calling side, rather than returning it as-is?
```

---

## STEP 4 — Repository pattern

**Related documents**: [architecture/repository-pattern.md](./architecture/repository-pattern.md) · [architecture/persistence.md](./architecture/persistence.md)

```
[ ] Is the Repository defined per Aggregate Root? (not per Entity/table, the same criterion as the harness's repository-abc/repository-impl checks)
[ ] Is the Repository interface (ABC) in the domain/ layer?
[ ] Is the Repository implementation in the infrastructure/persistence/ layer?
[ ] Is the Repository implementation class named SqlAlchemy<Aggregate>Repository?
[ ] Does the Repository lookup method name follow the find_<noun>s pattern (unified single-item/list)?
    → If split like find_by_id and find_all, unify into a single find_<noun>s(take, page, ...), with a single-item lookup done via take=1 + indexing (see the `AccountQuery.find_accounts` example in repository-pattern.md)
[ ] Does the Repository save method name follow the save_<noun> pattern? (a bare `save()` is forbidden)
    → e.g. `save_account`/`save_card`/`save_payment`/`save_refund`
[ ] Does the Repository have an update_<noun> method?
    → If so, remove it. Look up, modify via an Aggregate domain method, then upsert with a single save_<noun>()
[ ] Does save_<noun>() distinguish new vs. existing, behaving like an upsert? (look up the existing row via session.get() and branch)
[ ] Does a Handler directly manage the internal cascade order of save/delete?
    → If so, move that cascade logic into the Repository implementation (saving child Entities together too)
[ ] Does the Repository implementation convert a DB model (a SQLAlchemy Model) into a domain Aggregate object?
    → Does it go through something like _to_domain() that converts into Aggregate(...) rather than returning the DB row as-is?
[ ] Is the key/field name of a Repository find method's return type the plural of the domain object name?
    → Correct example: tuple[list[Order], int] or { "orders": [...], "count": ... }
    → Incorrect example: { "items": [...], "count": ... }, { "result": [...] }
[ ] Is a dynamic filter condition applied only when a value is present? (if account_id: stmt = stmt.where(...))
[ ] Does the count query apply the same filter conditions as the main query?
```

---

## STEP 5 — DI (`Depends`) / module boundaries / cross-domain / infrastructure services

**Related documents**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/bootstrap.md](./architecture/bootstrap.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Is each domain organized as a src/<domain>/ package unit?
    → Never split by layer (a routers package, a services package) (the same criterion as the harness's directory-structure check)
[ ] Does a single domain package contain all 4 layers — domain/application/interface/infrastructure?
[ ] Is the ABC ↔ implementation binding for a Repository/Adapter/Technical Service done via a Depends factory function in interface/rest/<domain>_router.py?
    → The point corresponding to NestJS's { provide, useClass } is the factory function itself. If a route function instantiates the implementation directly, split it out into a factory function
[ ] Does an Application Handler directly import another domain's Repository/Handler?
    → If so, switch to the Adapter pattern: an ABC in application/adapter/, the implementation in infrastructure/
[ ] Do two domains import each other at the top level, causing a circular import?
    → First re-examine the Bounded Context boundary. If only a type hint is needed, use TYPE_CHECKING; if needed at runtime, resolve it with a function-local deferred import
[ ] Is technical infrastructure such as encryption, file storage, or an external API client implemented directly in an Application Handler?
    → If so, switch to the Technical Service pattern: an ABC in application/service/, the implementation in infrastructure/<concern>/
[ ] Is the Technical Service interface defined as an ABC in application/service/?
[ ] Is the Technical Service implementation located in infrastructure/<concern>/ and bound via a Depends factory?
[ ] Does the server handle the file binary directly for file upload/download?
    → If so, switch to the Presigned URL pattern. The server only issues the URL; the client and storage communicate directly
[ ] Does the file-owning Entity have file_key (fixed length) and extension columns?
    → Only metadata is stored in the DB; the file itself is stored in storage
[ ] Is StorageService split via the Technical Service pattern (an ABC in application/service/, the implementation in infrastructure/storage/)?
[ ] Does main.py's lifespan handle both startup (fail-fast validation, schema creation/migration) and shutdown (disposing the engine, waiting for in-flight requests)?
[ ] Does main.py have GET /health/live and GET /health/ready health-check routes?
    → Liveness is always 200; readiness is 503 while shutting down
[ ] Are pydantic_settings.BaseSettings-based configuration classes split by concern? (config/database_config.py, config/jwt_config.py, etc.)
[ ] Are required environment variables validated at BaseSettings instantiation time, halting app startup via sys.exit(1) on failure?
[ ] Are sensitive values such as a DB password, JWT secret, or external API key looked up from AWS Secrets Manager in production?
    → Never hardcode them directly in an environment variable. Look them up via SecretService (the Technical Service pattern) with a TTL cache
```

---

## STEP 6 — Typing pattern

**Related documents**: [conventions.md](./conventions.md) section 4

```
[ ] Is the Aggregate Root written as a plain class (__init__ + methods), not a @dataclass?
[ ] Are the Entity/Value Object/Domain Event written as @dataclass(frozen=True)?
[ ] Is the Command/Query defined as a @dataclass?
[ ] Is typing.Any used anywhere?
    → If so, replace it with a concrete type or a Union/generic
[ ] Is a nullable field coming from the DB shaped as T | None?
[ ] Is a domain state value (status, etc.) defined as an enum or a Literal type instead of str?
[ ] Is the Handler execute() method's return type explicit?
[ ] Is a route function's return type explicit, matching response_model?
[ ] Is the datetime storage/lookup approach consistent across this project? (unified as either UTC-naive or timezone-aware, never mixed)
[ ] Is a type alias used for a complex Union type?
    → Correct example: OrderDomainEvent = Union[OrderPlaced, OrderCancelled]
```

---

## STEP 7 — Error handling

**Related documents**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Does the Domain/Application layer raise an HTTP-related exception such as HTTPException?
    → If so, replace it with a plain Exception subclass from domain/errors.py
[ ] Are domain exceptions defined in domain/errors.py as a class hierarchy (a <Domain>Error superclass + concrete subclasses)?
    → Never raise a free-form string directly
[ ] Does each domain exception have a <Domain>ErrorCode value from error_codes.py as its code attribute?
[ ] Does every entry in <Domain>ErrorCode have a fixed SCREAMING_SNAKE_CASE string value?
[ ] Do domain exceptions and error codes exist 1:1?
    → Avoid a situation where there's an exception with no code, or vice versa
[ ] Is main.py's @app.exception_handler the sole HTTP conversion point?
    → If a route function/Handler individually catches an exception and builds an HTTP response, remove it
[ ] Is a more specific exception type (OrderNotFoundError) registered via @app.exception_handler before its supertype (OrderError)?
[ ] Does the error-response body follow the 4-field shape — statusCode, code, message, error?
    → If it's just { "message": "..." }, standardize it via build_error_response(), etc.
[ ] Does a Pydantic validation failure (422) response also follow the same 4-field shape, with code fixed to VALIDATION_FAILED?
[ ] Is an unmapped exception exposed as a plain 500, with no stack trace included in the response body?
```

---

## STEP 8 — REST API endpoints

**Related documents**: [architecture/api-response.md](./architecture/api-response.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md)

```
[ ] Is the URL made of plural noun resources, not verbs?
    → Correct example: GET /orders, POST /orders
    → Incorrect example: GET /get-orders, POST /create-order
[ ] Is the resource name plural?
[ ] Does the URL use only lowercase kebab-case? (separate from the snake_case used in Python code)
[ ] Is the HTTP method used correctly? (GET for lookup, POST for creation, PUT for a full update, PATCH for a partial update, DELETE for deletion)
[ ] Is a non-CRUD action expressed as a sub-resource path?
    → Correct example: POST /orders/{order_id}/cancel
[ ] Is nested-resource depth within 2 levels?
[ ] Is the route decorator's status_code set appropriately for the HTTP method?
    → GET/PUT/PATCH: 200 (default), POST: 201, DELETE: 204
[ ] Does the URL avoid a trailing slash (/) or a file extension (.json)?
[ ] Is the list-query response's field name the plural of the domain object name (orders, count)?
    → Generic field names such as result, data, items are forbidden
[ ] Is the response free of a generic wrapper ({"success": true, "data": {...}})?
    → The Pydantic model is serialized as the top-level JSON; error vs. success is distinguished by the HTTP status code
[ ] Is pagination unified as page (0-based) and take?
[ ] Does a router that needs authentication have APIRouter(dependencies=[Depends(get_current_user)]) applied?
    → Applied uniformly at the router level, not redeclared on every individual route
[ ] Does get_current_user go through fastapi.security.HTTPBearer + JWT verification?
    → If a header such as X-User-Id is trusted without verification, replace it with the JWT pattern from authentication.md
[ ] Does the JWT payload carry only the minimal information, such as user_id?
    → It never carries frequently-changing or sensitive information such as email, role, permissions
[ ] Is Rate Limiting registered globally via slowapi, etc.? (default_limits)
[ ] Is a write (POST/PUT/PATCH/DELETE) route's limit stricter than a query (GET)'s?
[ ] Is the health-check (/health/*) endpoint free from an overly strict limit?
```

---

## STEP 9 — Pydantic response models / OpenAPI documentation

**Related documents**: [conventions.md](./conventions.md) section 8

```
[ ] Does every route function have response_model specified (or is it clearly a 204 route with none)?
[ ] Does the route function's return type hint match response_model?
[ ] Is Field(..., min_length=, max_length=, description=) applied appropriately to a request Pydantic model's fields?
[ ] Is an Optional field shaped as T | None = Field(None, description=...)?
[ ] Are array/nullable fields expressed with just a type hint (list[...], T | None), with no extra decorator needed?
[ ] Is Field/validation written only on the Interface Pydantic model (schemas.py), not on the Application Result (dataclass)?
[ ] Is deprecated=True marked on an endpoint slated for removal?
    → Rather than deleting it immediately, mark it deprecated and allow a migration period
[ ] Are /docs, /redoc, /openapi.json exposed correctly with no extra configuration? (FastAPI(title=...) alone is enough)
```

---

## STEP 10 — Import organization

**Related documents**: [conventions.md](./conventions.md) section 7

```
[ ] Are imports organized into 2 groups (standard library/third-party → a blank line → internal modules)?
[ ] Are relative imports used consistently within the same domain package?
[ ] Is another top-level package (src.database, src.common, another domain) referenced via an absolute import?
[ ] Does __init__.py avoid re-exporting a submodule?
    → Keep __init__.py empty or as just a package marker, so the import path isn't split into two forms
[ ] Is TYPE_CHECKING or a function-local deferred import used to resolve a circular import?
    → Is there any remaining cycle where top-level imports point at each other?
```

---

## STEP 11 — Router composition / authentication / scheduling / graceful shutdown

**Related documents**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/scheduling.md](./architecture/scheduling.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/authentication.md](./architecture/authentication.md)

```
[ ] Does each domain's APIRouter have a prefix and tags? (APIRouter(prefix="/orders", tags=["Order"]))
[ ] Does main.py compose each domain router via app.include_router(...)?
[ ] Does a router that needs authentication have a shared Depends attached, with a router that doesn't (/auth/sign-in, /health/*) excluded?
[ ] Is the Domain layer free of any dependency on the authentication context (the current user, etc.)?
    → requester_id, etc. are carried into the Command/Query as a value already verified in the Interface layer
[ ] Is the Scheduler (APScheduler, etc.) located in infrastructure/scheduling/?
    → Never put scheduling code directly in the Application/Domain layer
[ ] Does the Scheduler only call an Application Handler, without executing business logic directly?
[ ] Does the Scheduler's job function explicitly log a failure via try-except + logger.exception?
    → APScheduler silently swallows an exception inside a job, so a failure is unobservable unless logged directly
[ ] Is a DB row lock or a distributed lock applied to a batch that must never run on multiple instances at once?
[ ] Does main.py's lifespan wait for in-progress jobs to finish via scheduler.shutdown(wait=True) before shutting down?
[ ] Is a flag such as app.state["is_shutting_down"] set before resource cleanup (engine.dispose(), etc.)?
    → So the readiness probe detects the shutdown first
[ ] Does GET /health/live always return 200, even while shutting down?
```

---

## STEP 12 — DB / infrastructure patterns

**Related documents**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/container.md](./architecture/container.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] Does the SQLAlchemy model include created_at, updated_at, deleted_at (nullable) columns?
    → An immutable record (a transaction history, etc.) may omit updated_at/deleted_at
[ ] Does deletion use setting deleted_at = datetime.utcnow() (soft delete), not session.delete()?
    → Hard delete is forbidden
[ ] Do all lookup queries include the deleted_at.is_(None) condition?
[ ] Is the transaction boundary managed as one HTTP request = one AsyncSession (via Depends(get_session)'s request-scope caching)?
[ ] Are writes spanning multiple Repositories/Technical Services assembled with the same session (the same Depends(get_session) instance)?
[ ] Does the production startup path call Base.metadata.create_all()?
    → If so, remove it and replace it with an Alembic migration (alembic upgrade head). create_all is local/test-only
[ ] Was an Alembic migration file generated after a schema change? (alembic revision --autogenerate)
[ ] Is a Repository's dynamic filter condition applied only when a value is present?
[ ] Does a Repository find method unify single-item/list lookups into a single find_<noun>s? (the same criterion as STEP 4)
[ ] Are sensitive values such as a DB password, JWT secret, or external API key looked up from AWS Secrets Manager in production?
    → Is a hardcoded default in an environment variable (such as "test") avoided in production too?
[ ] Does SecretService have a TTL cache applied to avoid repeated lookups?
[ ] Is fail-fast validation performed at pydantic_settings.BaseSettings instantiation time?
[ ] Is local development isolated via docker-compose.yml (Postgres + LocalStack)?
[ ] Does every infrastructure service have a healthcheck configured?
[ ] Is the Dockerfile a multi-stage build, with build tools (gcc, etc.) absent from the final image?
[ ] Is the Dockerfile's CMD in exec form (["uvicorn", ...])?
    → Shell form delays SIGTERM delivery, so graceful shutdown doesn't work
[ ] Does the container run as a non-root user?
[ ] Are logs structured JSON with snake_case field names? (structured fields passed via extra=)
[ ] Is a Correlation ID injected into every request via contextvars-based middleware, and automatically included on every log line?
[ ] Does the Domain layer avoid logging? (the same criterion as STEP 2)
```

---

## STEP 13 — Testing patterns

**Related documents**: [architecture/testing.md](./architecture/testing.md)

```
[ ] Are Domain-layer unit tests written by instantiating the Aggregate directly, with no framework or mocks?
[ ] Does an Application Handler test replace the Repository/Adapter/Technical Service with unittest.mock.AsyncMock?
[ ] Does an E2E test verify the actual request flow via httpx.ASGITransport + app.dependency_overrides?
[ ] Does an E2E/integration test use testcontainers (Postgres, and LocalStack if needed)?
    → Never connect directly to a production DB/AWS service
[ ] Is there a test for an Aggregate-invariant violation? (pytest.raises(SpecificError))
[ ] Is there a test verifying whether a Domain Event was collected (pull_events())?
[ ] Is error verification type-based via pytest.raises(SpecificErrorClass)? (comparing a message string is forbidden)
[ ] Are Domain/Application unit tests placed in a consistent location, such as tests/unit/domain/, tests/unit/application/?
[ ] Is an E2E test placed in the tests/ directory as test_<domain>_e2e.py?
[ ] Is pytest.ini's asyncio_mode setting applied consistently across the whole project?
[ ] Does test naming follow the test_<method>_<condition>_<expected_result> pattern (either a descriptive style or an English style — whichever is consistent within a file)?
```

---

## STEP 14 — Final overall-consistency check

**Related documents**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] Are all domain exceptions that can be raised mapped in main.py's @app.exception_handler?
    → If any exception is missing, add a handler for it
[ ] Is a newly added Handler/Repository/Adapter/Technical Service actually wired via a Depends factory?
    → Is there any dead code that's only defined and never referenced via Depends from any route?
[ ] If a Command/Query/Result was newly created, does the Interface Pydantic model (schemas.py) only perform thin conversion wrapping it?
[ ] Does the code you worked on avoid leaving behind a TODO, print(), or a temporary comment?
[ ] Is the Ubiquitous Language reflected consistently in the code (class names, function names, variable names)?
[ ] Is the comment style mostly # inline comments, with a docstring used only for a one-line summary on a public API?
[ ] Is logger output in a structured shape? (extra=, snake_case field names)
[ ] Does the commit message follow the Conventional Commits format (feat/fix/refactor + scope)?
[ ] Is the commit message's scope a service domain name (order, account, payment, etc.)?
    → For a change spanning multiple domains, omit the scope or use a higher-level concept
[ ] Is the commit message's description a plain descriptive sentence, with no trailing period?
[ ] Does the commit message's body explain "why" the change was made?
[ ] If there's a BREAKING CHANGE, is it marked via a footer or a ! after the type?
[ ] Does the branch name follow the Conventional Branch format (<type>/<scope>-<description>), with every word in kebab-case?
[ ] Is a PR used to land changes, instead of committing/pushing directly to the main branch?
[ ] Does the PR title match the Conventional Commits format, with the body following a Summary + Test plan shape?
[ ] Is the merge strategy Squash and merge?
[ ] Are there no FAIL items when harness/harness.py is run?
```

---

## STEP 15 — Design-deliverable shape (for design-stage work)

**Related documents**: [development-process.md](../../../docs/development-process.md) (root, shared) · [reference.md](./reference.md)

> Applies only when a design-stage (RA, SD, DM, TD) deliverable was produced. This STEP is a language-agnostic deliverable format, so it follows the same criteria as the NestJS implementation.

```
[ ] RA deliverable: does a functional requirement include an FR-### number, a description, Acceptance Criteria, and a priority (MoSCoW)?
[ ] RA deliverable: does a use case include a UC-### number, an Actor, preconditions, the main flow (Happy Path), exception flows, and postconditions?
[ ] RA deliverable: does the constraints summary table include tech stack, external systems, schedule, regulatory, and traffic items?
[ ] SD deliverable: does the subdomain classification table include the type (Core/Supporting/Generic) and the implementation strategy?
[ ] SD deliverable: does the Bounded Context definition include responsibilities, core concepts, and its subdomain?
[ ] SD deliverable: does the Context Map include the relationship type (Partnership/Shared Kernel/Customer-Supplier/Conformist/ACL/OHS·PL) and the reason it was chosen?
[ ] DM deliverable: does the event-storming mapping table include Actor/Command/Aggregate/Domain Event/Policy/External System columns?
[ ] DM deliverable: does the Ubiquitous Language glossary include Term (English)/Term (localized)/Definition/Context/Notes columns?
[ ] DM deliverable: when the same word is used with a different meaning across different Contexts, is that noted in the glossary?
[ ] DM deliverable: does the per-Aggregate domain model structure include the Root/Entity list/VO list/relationships?
[ ] DM deliverable: does the Domain Event detail list include event name/trigger condition/included data/follow-up processing (Policy) columns?
[ ] DM deliverable: do business rules/invariants include an INV-### number and how a violation is handled?
[ ] TD deliverable: does the file-structure tree include all 4 layers — domain/application/infrastructure/interface?
[ ] TD deliverable: does the Depends binding configuration specify the ABC ↔ implementation mapping? (per factory function)
[ ] TD deliverable: does the Aggregate design include the Root/internal Entities/internal VOs/external references (IDs)/creation rules/invariants?
[ ] TD deliverable: does the Repository interface definition include find_<noun>s/save/delete methods?
[ ] TD deliverable: does the Application Handler definition include the use-case mapping/processing flow/transaction scope/failure handling?
[ ] TD deliverable: does the Event flow diagram include the sync/async processing approach and compensating transactions?
[ ] IM deliverable: is the work proceeding via Vertical Slicing (implementation per use case)?
    → Implement all layers together per use case (vertical), not per layer (horizontal)
[ ] IM deliverable: is the slice plan organized as slice number/use case/included files/priority?
```

---

## STEP 16 — For guide-editing work

**Related documents**: [development-process.md](../../../docs/development-process.md) (root, shared) · [conventions.md](./conventions.md)

> Applies only when editing the guide itself, not doing code work.

```
[ ] Is the newly added or modified explanation written in English?
[ ] Does the new rule come with both a correct example (# correct approach) and an incorrect example (# incorrect approach)?
[ ] Does the example you wrote avoid violating this guide's other rules (file naming, imports, typing, Pydantic documentation, etc.)?
    → If it does, fix the example first, then finalize the rule
[ ] Does the new rule avoid contradicting a "known gap" that actually exists in this repository's examples/ code?
    → If architecture/*.md has already documented it as a gap, keep that gap description and have this document describe only the target state
[ ] When changing the guide, is a PR created from a new branch rather than the main branch?
```

---

## How to use this checklist

An AI Agent performs self-review in the following order after finishing a task:

1. Go through **STEP 1–14 in order**.
2. When a violation is found, **fix the file immediately** and check it off.
3. After fixing it, **verify related files (Depends factories, import references, etc.) aren't affected** either.
4. If it was design-stage work, also go through **STEP 15**.
5. If it was guide-editing work, also go through **STEP 16**.
6. After all checks are done, run `harness/harness.py <projectRoot>` for a final confirmation that every mechanically checkable item passes with no FAILs.

> The checklist summarizes the guide's rules.
> If an item's intent is unclear, refer to the corresponding document:
> - STEP 1 File structure and naming → [conventions.md](conventions.md) sections 1-3
> - STEP 2 The Domain layer → [tactical-ddd.md](architecture/tactical-ddd.md), [domain-service.md](../../../docs/architecture/domain-service.md) (root, shared), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 Layer architecture / CQRS / events → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [domain-events.md](architecture/domain-events.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 4 Repository pattern → [repository-pattern.md](architecture/repository-pattern.md)
> - STEP 5 DI (Depends) / module boundaries → [module-pattern.md](architecture/module-pattern.md), [shared-modules.md](architecture/shared-modules.md), [bootstrap.md](architecture/bootstrap.md)
> - STEP 6 Typing pattern → [conventions.md](conventions.md) section 4
> - STEP 7 Error handling → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API endpoints → [api-response.md](architecture/api-response.md), [rate-limiting.md](architecture/rate-limiting.md), [authentication.md](architecture/authentication.md)
> - STEP 9 Pydantic documentation → [conventions.md](conventions.md) section 8
> - STEP 10 Imports → [conventions.md](conventions.md) section 7
> - STEP 11 Router/authentication/scheduling/graceful shutdown → [module-pattern.md](architecture/module-pattern.md), [scheduling.md](architecture/scheduling.md), [graceful-shutdown.md](architecture/graceful-shutdown.md)
> - STEP 12 DB/infrastructure → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [secret-manager.md](architecture/secret-manager.md), [observability.md](architecture/observability.md)
> - STEP 13 Testing patterns → [testing.md](architecture/testing.md)
> - STEP 14 Overall consistency → refer to the full set of documents
> - STEP 15 Design-deliverable shape → [development-process.md](../../../docs/development-process.md) (root, shared) Agents 1-5
> - STEP 16 Guide edits → [CLAUDE.md](../CLAUDE.md) guide-management principles
