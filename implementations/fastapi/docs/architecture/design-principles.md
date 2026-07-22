# Core Design Principles Summary

Among the principles already covered across the other 21 documents in this repository, this condenses the ones that need to be revisited most often in the FastAPI implementation, in composition order (structure → layers → errors/DI → ID/events/DTO/async). Each item links to its detailed document — this is only a summary of the existing documents, not a new set of conflicting rules.

1. **Domain-first directory structure** — place the four layers `domain/application/interface/infrastructure` under `src/<domain>/`. The package-splitting criterion is the Bounded Context, not the technical layer. ([directory-structure.md](directory-structure.md))

2. **The Domain layer is framework-agnostic** — it imports no external library such as `fastapi`, `sqlalchemy`, or `aioboto3`. Only standard library modules such as `dataclasses`/`abc`/`datetime`/`enum` are used. ([tactical-ddd.md](tactical-ddd.md), [layer-architecture.md](layer-architecture.md))

3. **Mutable state is a plain class, immutable values are `frozen dataclass`** — the Aggregate Root (`Account`) is a plain class with `__init__` plus domain methods, while Entities (`Transaction`), Value Objects (`Money`), and Domain Events are `@dataclass(frozen=True)`. ([tactical-ddd.md](tactical-ddd.md))

4. **Business rules are encapsulated in Aggregate methods; Handlers only orchestrate** — invariants are validated immediately upon entering a domain method such as `deposit()`/`withdraw()`. Application Handlers only perform: Repository lookup → invoke Aggregate method → save. ([layer-architecture.md](layer-architecture.md))

5. **Repository is scoped per Aggregate — the ABC lives in `domain/`, the implementation in `infrastructure/`** — a child Entity (`Transaction`) does not get its own Repository; it is looked up and saved only through the Aggregate Root's Repository. ([repository-pattern.md](repository-pattern.md))

6. **DI is via `Depends` factory functions — no dedicated container** — the binding between an ABC and its implementation for Repositories/Technical Services/Adapters is handled by factory functions (`_repo`, `_notification_service`) in `interface/rest/*_router.py`. This is the sole point corresponding to NestJS's `{ provide, useClass }`. ([module-pattern.md](module-pattern.md))

7. **Command/Query Handlers are `XxxHandler` + `async def execute()`** — Commands and Queries are physically separated into `application/command/` and `application/query/`. Introducing a CommandBus/QueryBus is optional, and this repository has not adopted one yet. ([cqrs-pattern.md](cqrs-pattern.md))

8. **Technical Services separate interface from implementation** — technical infrastructure concerns such as sending email (`NotificationService`) place the ABC in `application/service/` and the implementation in `infrastructure/<concern>/`. The same structure applies when adding file storage or a Secrets Manager. ([layer-architecture.md](layer-architecture.md), [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md))

9. **Errors are an exception class hierarchy; HTTP conversion happens only in `main.py`** — `domain/errors.py` only raises plain `Exception`s and knows nothing about HTTP. `@app.exception_handler` is the sole conversion point. Each exception carries a unique `code` from the enum in `domain/error_codes.py`, and `build_error_response()` in `src/common/error_response.py` assembles the 4-field response (`statusCode`/`code`/`message`/`error`) required by the root docs. Pydantic validation failures (422) also follow the same shape with `code: VALIDATION_FAILED`. ([error-handling.md](error-handling.md))

10. **IDs are generated in a Domain factory classmethod, as 32-character hyphen-less hex** — `Account.create()` is the sole creation path, and `common/generate_id.py` returns `uuid.uuid4().hex` (no hyphens), used consistently across every domain. ([aggregate-id.md](aggregate-id.md))

11. **Domain Events are collected via `pull_events()`; publishing goes through Outbox → SQS** — `repo.save()` commits the Aggregate state and the Outbox row in the same transaction, and the Command Handler returns immediately right after (no synchronous drain). An independently, periodically running `OutboxPoller` publishes from the Outbox to SQS, and `OutboxConsumer` receives from SQS and invokes `application/event/<event>_event_handler.py` — this handler calls `NotificationService` per event type. Because the Aggregate state and the Outbox row are committed in the same transaction, no dual-write occurs. ([domain-events.md](domain-events.md))

12. **Interface DTOs (Pydantic) perform only thin conversion** — the `BaseModel`s in `schemas.py` define only request/response shape, and merely wrap the Result object from `application/query/result.py`. Format validation (422, Pydantic) is not conflated with business-rule violations (400, Domain exceptions). ([cross-cutting-concerns.md](cross-cutting-concerns.md), [directory-structure.md](directory-structure.md))

13. **Async I/O uses `async`/`await` consistently across every layer** — Repositories, Technical Services, Handlers, and even route functions are all `async def`. Mixing in synchronous functions blocks the event loop and delays the handling of other requests — be especially careful not to accidentally call a synchronous SDK in `infrastructure/` (this is also why an async client such as `aioboto3` was explicitly chosen). ([layer-architecture.md](layer-architecture.md))

---

All 13 items above are followed as-is by the actual code in `examples/`. Rate Limiting is implemented with `slowapi` — see [rate-limiting.md](rate-limiting.md).

### Related documents

- [../../CLAUDE.md](../../CLAUDE.md) — keyword → document index
- `../../../../docs/implementations/fastapi.md` — coverage audit against the 21 root topics
