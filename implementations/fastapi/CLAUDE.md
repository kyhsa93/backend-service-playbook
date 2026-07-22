# FastAPI Implementation Guide

Design/implementation guide for a DDD-based FastAPI (Python) server project.
It follows the four-layer structure `src/<domain>/{domain,application,interface,infrastructure}/`.

> **Design principles (framework-agnostic)** are covered in the root [CLAUDE.md](../../CLAUDE.md) and `../../docs/architecture/`.
> This document focuses on FastAPI implementation details.

## Documents to reference while working

### Layers / structure

| Task / keyword | Document to read |
|---------------|----------|
| Adding a new domain, domain module template, Order example | `docs/reference.md` — code can be generated immediately with `scripts/create_domain.py` (see "Scaffolding" below) |
| Project structure, directory layout, file/class naming | `docs/architecture/directory-structure.md` |
| Layer responsibilities, Domain / Application / Interface / Infrastructure, `Depends`-based DI | `docs/architecture/layer-architecture.md` |
| Repository pattern, ABC interface + SQLAlchemy implementation, method naming | `docs/architecture/repository-pattern.md` |
| Technical Service, abstracting technical infrastructure concerns (`notification_service.py` pattern) | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, Domain Event, modeling approach (dataclass vs. plain class) | `docs/architecture/tactical-ddd.md` |
| Aggregate ID generation, UUID hex, `.hex` vs. hyphenated | `docs/architecture/aggregate-id.md` |
| Strategic design, Subdomain, Bounded Context, Context Map | `../../docs/architecture/strategic-ddd.md` (root shared document) |
| Choosing a cross-BC communication pattern, sync vs. async, ACL | `../../docs/architecture/cross-domain-communication.md` (root shared document) |
| Cross-domain calls, Adapter pattern implementation (ABC + `infrastructure/` implementation), ACL example code | `docs/architecture/cross-domain.md` |
| Core design principles summary, cheat sheet of 13 rules | `docs/architecture/design-principles.md` |
| No DI container, `Depends` factory = binding point, Python package = module, resolving circular imports | `docs/architecture/module-pattern.md` |
| Where shared code that doesn't belong to any domain lives, `src/common/`, `src/config/`, `src/auth/`, `src/outbox/` | `docs/architecture/shared-modules.md` |

### Data / transactions

| Task / keyword | Document to read |
|---------------|----------|
| SQLAlchemy `AsyncSession`, transaction boundaries, soft delete | `docs/architecture/persistence.md` |
| Alembic migrations, the limits of `Base.metadata.create_all` | `docs/architecture/persistence.md` |
| Domain Event, `pull_events()`, Outbox pattern, event handler idempotency | `docs/architecture/domain-events.md` |
| CQRS, Command/Query Handler, `execute()`, criteria for introducing a Bus | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| Task / keyword | Document to read |
|---------------|----------|
| REST endpoints, `APIRouter`, Pydantic request/response models | `docs/architecture/directory-structure.md` |
| API response structure, pagination, Result object, list/single-item response shape | `docs/architecture/api-response.md` |
| API documentation, OpenAPI/Swagger completeness (`summary`/`description`/`responses=`, `Field(description=...)`), what the `api-documentation` harness rule checks | `docs/architecture/api-response.md` (see also `../../docs/architecture/api-response.md`, the root shared document) |
| Authentication, JWT, Bearer token, `Depends(get_current_user)` | `docs/architecture/authentication.md` |
| Middleware, Correlation ID, request pipeline | `docs/architecture/cross-cutting-concerns.md` |
| Error handling, `domain/errors.py`, `@app.exception_handler`, error response shape | `docs/architecture/error-handling.md` |
| Presigned URL, file upload/download, S3/`aioboto3` | `docs/architecture/file-storage.md` |
| App bootstrap, `main.py`, constructing `FastAPI(...)`, registering routers/exception handlers, auto-generated Swagger (`/docs`) | `docs/architecture/bootstrap.md` |
| Rate limiting, `slowapi`, request throttling, 429 | `docs/architecture/rate-limiting.md` |

### Operations / infrastructure

| Task / keyword | Document to read |
|---------------|----------|
| Environment configuration, `pydantic-settings`, `BaseSettings`, fail-fast validation | `docs/architecture/config.md` |
| Secret management, AWS Secrets Manager, TTL cache | `docs/architecture/secret-manager.md` |
| `lifespan`, startup/shutdown, SIGTERM, health-check endpoint | `docs/architecture/graceful-shutdown.md` |
| Local development environment, `docker-compose.yml`, LocalStack | `docs/architecture/local-dev.md` |
| Dockerfile, multi-stage build, uvicorn | `docs/architecture/container.md` |
| Logging, structured JSON logs, Correlation ID, metrics/tracing | `docs/architecture/observability.md` |

### Async / scheduling

| Task / keyword | Document to read |
|---------------|----------|
| Scheduling, `BackgroundTasks`, APScheduler, criteria for choosing Celery | `docs/architecture/scheduling.md` |
| Outbox Poller/Consumer, batch jobs, idempotency | `docs/architecture/scheduling.md` |

### Quality / verification

| Task / keyword | Document to read |
|---------------|----------|
| Testing strategy, Domain unit tests, Application mock tests (`unittest.mock`), E2E (testcontainers) | `docs/architecture/testing.md` |
| `pytest-asyncio`, `httpx.ASGITransport`, `dependency_overrides` | `docs/architecture/testing.md` |
| Running the harness, list of check rules | `harness/README.md` |
| Harness design principles (evaluates only architectural-rule compliance, not business-domain knowledge) | `../../docs/harness.md` (root shared document) |

## FastAPI implementation principles summary

- Packages: `src/<domain>/domain/`, `application/{command,query,service}/`, `infrastructure/{persistence,notification}/`, `interface/rest/`
- Repository: ABC in `domain/`, SQLAlchemy implementation in `infrastructure/`
- Technical Service: ABC in `application/service/` (e.g. `notification_service.py`), implementation in `infrastructure/<concern>/`
- CQRS: `XxxHandler` class + `async def execute(self, cmd/query)` method
- Errors: exception classes defined in `domain/errors.py`, mapped to HTTP via `@app.exception_handler` in `main.py`
- Soft delete: `deleted_at: datetime | None` — hard delete is forbidden
- DI: FastAPI `Depends` injects Handler/Repository/Service into routers (no dedicated DI container)

## Implementation verification

```bash
./harness.sh <projectRoot>
```

## Lint / formatting

Both `examples/` and `harness/` are linted/formatted with [ruff](https://docs.astral.sh/ruff/) (replacing flake8+isort+black).
Configuration lives in `pyproject.toml` (`implementations/fastapi/`) — the rule set is `E` (pycodestyle)/`F` (pyflakes)/`I` (isort),
with `line-length = 120` (relaxed from a 100-character limit, since the many comments produce line-wrap diffs unrelated to the actual change).
`harness/tests/fixtures/` contains code that intentionally violates rules for harness regression testing, so it is excluded from linting.

```bash
# Run from implementations/fastapi/ (ruff is included in examples/requirements.txt)
ruff check .           # lint
ruff check --fix .     # fix auto-fixable issues
ruff format .          # apply formatting
ruff format --check .  # check formatting violations only (same as CI)
```

CI (`.github/workflows/fastapi.yml`) runs `ruff check .` and `ruff format --check .` before the tests, failing on any violation.

## Scaffolding — new domain generator

Based on the actual code in `docs/reference.md`, `examples/src/account`, and `examples/src/card`,
this Python script generates in one shot: an Aggregate (single status field + PENDING/ACTIVE/CANCELLED),
CQRS Command/Query Handlers, one domain event (published on cancel), Repository (ABC/implementation),
Router, Pydantic schemas, and an Alembic migration. FastAPI has no DI container — the eventType → handler
routing is a single composition root, `build_event_handlers()` in `src/outbox/event_handlers.py`, for the
entire process (OutboxPoller/OutboxConsumer independently reuse it — see domain-events.md), so beyond
creating the new domain directory, the script must also wire up `main.py` (router registration),
`event_handlers.py` (the shared composition root `build_event_handlers()`), and `migrations/env.py`
(the model import Alembic needs to detect).

```bash
# Default: generates under ../examples/src/<domain>/, only prints the snippets to paste in
# without touching main.py/event_handlers.py/migrations
python3 scripts/create_domain.py Coupon

# With --wire, automatically wires up main.py/event_handlers.py/migrations/env.py/migrations/versions/
python3 scripts/create_domain.py Coupon --wire

# To generate into a different project (e.g. one cloned from this repo as a template), specify --out
python3 scripts/create_domain.py Coupon --out /path/to/other-project/src --wire
```

Verify immediately after generation with `ruff check . && ruff format --check . && bash harness.sh <projectRoot>` —
it has actually been tested against new domains unrelated to Account/Card (including multi-word/irregular-plural
names such as Coupon, LoyaltyCategory), confirming the harness reports `236 passed  PASS` (0 FAIL). When `--wire`
runs, it automatically runs `ruff check --fix`/`ruff format` on the wired files, rather than trying to perfectly
reproduce via text insertion alone the alphabetical-order differences of where an import gets inserted depending
on the domain name (e.g. after "card" and before "common" vs. after "database" and before "outbox") — that is
left to ruff's isort implementation. Because it applies a naive pluralization rule (+s/+es/y→ies) on top of
snake_case, a domain with a genuinely irregular plural (e.g. person→people) may require manually adjusting
generated names such as `find<Domains>`/`<domains>`. What gets generated is a structural skeleton (an empty,
CRUD-shaped starting point) — the actual business rules, error messages, and fields are filled in by hand
after generation.

## Example code

`examples/` contains a full example implementation of the Account domain.
Use it as a template when working on a new domain (just change the domain name).
