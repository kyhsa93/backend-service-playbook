# Harness — FastAPI project structure/naming rule checks

A static-analysis tool that applies the **mechanically checkable items** among the guide's rules from `docs/` (shared) + `docs/architecture/*.md` (the FastAPI implementation) to an external FastAPI project. It follows the design principles in the root [`../../../docs/harness.md`](../../../docs/harness.md) — it only evaluates architectural-rule compliance, and never presumes knowledge of a specific business domain such as the Account domain in `examples/`.

## Structure

```
harness/
  harness.py                     CLI entry — defines the rule list + aggregates/prints results
  rules/
    __init__.py
    common.py                    shared types (Finding, RuleResult) and helpers (collect_py_files, read, rel, norm, ...)
    file_naming.py                a module per rule (one file per rule)
    repository_abc.py
    repository_impl.py
    repository_naming.py
    handler_placement.py
    domain_purity.py
    directory_structure.py
    shared_infra.py
    event_placement.py
    layer_dependency.py
    no_notification_dependency_in_command.py
    outbox_no_sync_drain.py
    cqrs_pattern.py
  tests/
    test_rules.py                pytest — parameterizes and iterates over the fixtures/ below
    fixtures/<rule>/good/         the minimal fixture that must pass that rule
    fixtures/<rule>/bad-*/        a fixture that must fail by violating that rule
```

Every rule module has the signature `check(root: str, py_files: list[str]) -> RuleResult`, and they're run/printed in the order registered in `harness.py`'s `RULES` list. `py_files` is computed once by `harness.py` and passed to every rule.

## Usage

```bash
# From the repository root
bash implementations/fastapi/harness.sh <projectRoot>

# Or directly from the harness/ directory
cd implementations/fastapi/harness
python3 harness.py <projectRoot>
```

## Rule list

| Name | Module | Role |
|------|------|------|
| `file-naming` | `rules/file_naming.py` | Whether the file name is `snake_case.py` |
| `repository-abc` | `rules/repository_abc.py` | An ABC Repository (`ABC`/`abstractmethod` + `Repository`) → `domain/` |
| `repository-impl` | `rules/repository_impl.py` | A Repository implementation (`class XRepository`) → `infrastructure/` |
| `repository-naming` | `rules/repository_naming.py` | Fails if a `*Repository`/`*Query` ABC's abstract method name in `domain/` matches the `find_by_*`/`find_all`/`count*`/bare `save`/bare `delete`/`update_*` anti-pattern — only `find_<noun>s`/`save_<noun>`/`delete_<noun>` are allowed, a separate `update_*` method is forbidden (repository-pattern.md) |
| `handler-placement` | `rules/handler_placement.py` | `*_handler.py` → `application/command/` or `application/query/` |
| `domain-purity` | `rules/domain_purity.py` | Importing `fastapi`/`sqlalchemy`/`aioboto3`/`logging`/`structlog` in `domain/` is forbidden |
| `directory-structure` | `rules/directory_structure.py` | `src/<domain>/{domain,application,interface,infrastructure}` + `application/{command,query}` exist |
| `shared-infra` | `rules/shared_infra.py` | If `OutboxWriter` is referenced, confirms `outbox_writer.py`/`outbox_poller.py`/`outbox_consumer.py` exist in `src/outbox/`; if `TaskOutboxWriter` is referenced (or a misplaced file named `*task_queue*` exists), confirms `task_outbox_writer.py`/`task_outbox_poller.py`/`task_consumer.py` are placed in `src/task_queue/` |
| `event-placement` | `rules/event_placement.py` | `*_event_handler.py` → `application/event/`, `*_integration_event.py` → `application/integration-event/` |
| `layer-dependency` | `rules/layer_dependency.py` | AST-based — fails if `application/` directly imports `infrastructure/` (dependency inversion) |
| `no-notification-dependency-in-command` | `rules/no_notification_dependency_in_command.py` | Fails if a Command Handler directly depends on `NotificationService` (including its ABC) — it must go through the Outbox |
| `outbox-no-sync-drain` | `rules/outbox_no_sync_drain.py` | Fails if a Command Handler directly references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls something like `process_pending()`/`run_forever()` — it must return immediately after saving, and publishing/receiving Outbox → SQS is the sole responsibility of the independently, periodically running Poller/Consumer (domain-events.md, synchronous draining is forbidden) |
| `cqrs-pattern` | `rules/cqrs_pattern.py` | Fails if a file under `application/query/` references a write-capable Repository (the string `Repository`) — a Query must depend only on a read-only Query interface (cqrs-pattern.md) |
| `domain-layer-isolation` | `rules/domain_layer_isolation.py` | AST-based, a path-segment check — fails if `domain/` (of the same domain or another) imports `application/`·`infrastructure/`·`interface/`. A broader, structural version of `domain-purity`'s framework-name blocklist (layer-architecture.md) |
| `aggregate-no-public-setters` | `rules/aggregate_no_public_setters.py` | Fails if a `domain/` class has a public `@x.setter` property — a state change must happen only through a named domain method (`deposit()`, `suspend()`, etc.) (tactical-ddd.md) |
| `no-cross-aggregate-reference` | `rules/no_cross_aggregate_reference.py` | Dedicated to `src/payment/domain/{payment.py,refund.py}` — fails if `Payment` directly references the `Refund` class (or vice versa) as a field/constructor type; only an ID reference (`payment_id: str`) is allowed (domain-service.md) |
| `no-direct-env-access-outside-config` | `rules/no_direct_env_access.py` | Fails if `domain/`·`application/` calls `os.environ`/`os.getenv` directly — it must be encapsulated in a `BaseSettings` in `config/` (config.md) |
| `no-cross-bc-repository-in-application` | `rules/no_cross_domain_repository_import.py` | AST-based — fails if `application/` directly imports another domain's Repository/Query ABC such as `domain/repository.py`; it must go through an Adapter in `application/adapter/` (cross-domain.md) |
| `scheduler-in-infrastructure-only` | `rules/scheduler_in_infrastructure_only.py` | Fails if `domain/`·`application/` has APScheduler/Celery/`asyncio.create_task`-based scheduling — the Scheduler must be located only in `infrastructure/` (scheduling.md). `src/outbox/` is already treated as equivalent to infrastructure and is out of scope |
| `no-silent-except` | `rules/no_silent_except.py` | AST-based — fails if `application/`·`infrastructure/` has an `except ...: pass` (a body that's exactly a single `pass`); it must be logged and propagated (observability.md) |
| `dockerfile-conventions` | `rules/dockerfile_conventions.py` | Reads `<root>/Dockerfile`·`<root>/.dockerignore` directly, rather than `py_files` — checks for a multi-stage build (2+ `FROM`s), a `HEALTHCHECK`, and `.dockerignore` existing + a reasonable exclusion pattern (container.md) |
| `aggregate-id-format` | `rules/aggregate_id_format.py` | Checks the single file `src/common/generate_id.py` — whether it uses `uuid.uuid4().hex` (32-character hex, no hyphens), and whether the `str(uuid.uuid4())` (36-character, hyphens included) anti-pattern is absent (aggregate-id.md) |
| `error-response-schema` | `rules/error_response_schema.py` | Whether the field set of the response body (`JSONResponse(content=...)`) built by a handler registered via `@app.exception_handler(...)` exactly matches the 4 fields `statusCode`/`code`/`message`/`error` — traced recursively whether it's a dict literal or via a builder function (a Pydantic model's `.model_dump()`) (error-handling.md) |
| `soft-delete-filter` | `rules/soft_delete_filter.py` | Fails if a SQLAlchemy model in `infrastructure/persistence/` has `updated_at` (mutable state) but no `deleted_at`; if it has `deleted_at`, checks whether a `find_*` method looking it up includes a `<Model>.deleted_at.is_(None)` filter (persistence.md) |
| `typed-errors-only` | `rules/typed_errors_only.py` | Fails if `domain/`·`application/` throws a built-in generic exception with a string message, such as `raise Exception("...")`/`raise ValueError("...")` — a typed exception class from `domain/errors.py` must be raised (AGENTS.md, error-handling.md) |
| `rate-limit-wired` | `rules/rate_limit_wired.py` | Fails if a `Limiter` is only defined and not wired up in `main.py` (`app.state.limiter`/a `RateLimitExceeded` handler/`SlowAPIMiddleware`), or if `@limiter.limit(...)` is applied to no route, leaving it as dead code (rate-limiting.md) |
| `no-generic-response-keys` | `rules/no_generic_response_keys.py` | Fails if a `list[...]` field name in a Pydantic `BaseModel` list-response schema in `interface/rest/` is a generic key such as `result`/`data`/`items` — it must be the plural of the domain object name (`transactions`, `accounts`) (api-response.md) |
| `query-handler-no-raw-aggregate` | `rules/query_handler_no_raw_aggregate.py` | Fails if a `*Handler.execute()` return-type annotation under `application/query/` is a type imported from `domain/` (an Aggregate, etc.) — it must be converted into a dedicated Result type (`application/query/result.py`) before returning (api-response.md "The Result object"). Determines this structurally by "was it imported from domain/", without hardcoding a specific domain name |
| `no-cross-bc-domain-import` | `rules/no_cross_bc_domain_import.py` | Fails if `src/<bc>/domain/*.py` directly imports another BC's `domain/` package — another Aggregate may only be referenced by ID, an object reference is forbidden (tactical-ddd.md). Closes the remaining BC-to-BC domain↔domain gap between `domain-layer-isolation` (across layers) and `no-cross-aggregate-reference` (across Aggregates within payment) |
| `no-orm-autosync-in-prod-config` | `rules/no_orm_autosync_in_prod_config.py` | Fails if an `<expr>.metadata.create_all` call is found somewhere that isn't a test-only file (already excluded by `collect_py_files`) — a production schema change must be managed only via an Alembic migration (persistence.md); `create_all` is for test fixtures only |
| `api-documentation` | `rules/api_documentation.py` | Fails a `@router.get/post/put/patch/delete(...)` route if its keywords don't include both `summary=`/`description=`, or if no non-2xx status appears in `responses={...}` (route-level or the router-level `responses=` passed to `APIRouter(...)`) — an operationId/route with no metadata, or only the success response documented, isn't sufficient (api-response.md) |

## Rules considered but not adopted

- **`interface-no-infrastructure`** (fails if an `interface/rest/` router directly imports `infrastructure/`): in this repository, since FastAPI has no DI container, the `Depends` factory function itself in `interface/rest/*.py` is the "binding point" (see "② The `Depends` factory function" in [module-pattern.md](../docs/architecture/module-pattern.md), "The Infrastructure layer" in [layer-architecture.md](../docs/architecture/layer-architecture.md)) — a router directly importing an implementation such as `SqlAlchemyAccountRepository` to build a `Depends(_repo)` factory is the normal pattern the documents specify. Applying this rule as-is would fail every router in this repository, directly contradicting the pattern the documents actually show as example code. This rule may be valid in another language (a NestJS/Spring-family framework with a DI container), but it isn't applied to FastAPI.

## Regression tests

```bash
cd implementations/fastapi/harness
python3 -m pytest tests/
```

Every rule is verified with at least a `tests/fixtures/<rule>/good/` (must pass) and a `tests/fixtures/<rule>/bad-*/` (must fail) fixture.

When adding a new rule or modifying an existing one:
1. Implement (or modify) the logic in `rules/<rule>.py` — the `check(root, py_files) -> RuleResult` signature
2. Write the `tests/fixtures/<rule>/good/`, `tests/fixtures/<rule>/bad-*/` fixtures
3. Add the fixture to `tests/test_rules.py`'s parameterization list (for a new rule, also register it in `harness.py`'s `RULES`)
4. `python3 -m pytest tests/`
