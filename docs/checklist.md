# Self-Review Checklist

Go through the checklist below in order after finishing a task.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

**Verification rule**: when verifying each STEP, you must actually read the file in question and check it against the code. Passing an item without reading the code is forbidden.

---

## STEP 1 — File structure and naming

**Related docs**: [conventions.md](conventions.md) · [architecture/directory-structure.md](architecture/directory-structure.md)

```
[ ] Is there any file name that isn't kebab-case?
    → change it to kebab-case
[ ] Are DTO file names verb-first?
    → correct examples: get-order-request-param.ts, create-order-request-body.ts
[ ] Is an enum declared inline inside another file?
    → split it into a <domain>-enum.ts file at the module root
[ ] Is a constant declared inline inside another file?
    → split it into a <domain>-constant.ts file at the module root
[ ] Is the Controller file named <domain>-controller.ts?
[ ] Do the Domain-layer file names follow the rule?
    → Aggregate Root: <aggregate-root>.ts, Entity: <entity>.ts, Value Object: <value-object>.ts, Domain Event: <domain-event>.ts
[ ] Is the Repository interface file named <aggregate>-repository.ts? (in the domain/ layer)
[ ] Are the Query/Result files named <verb>-<noun>-query.ts / <verb>-<noun>-result.ts?
[ ] Do the Adapter file names follow the rule?
    → the interface: <external-domain>-adapter.ts (application/adapter/)
    → the implementation: <external-domain>-adapter-impl.ts (infrastructure/)
[ ] Do class names follow the naming rule?
    → Aggregate Root: a domain noun (Order, User)
    → Value Object: a domain concept (Money, Address)
    → Domain Event: past tense (OrderPlaced, OrderCancelled)
    → Repository interface: <Aggregate>Repository / implementation: <Aggregate>RepositoryImpl
    → Command: <Verb><Noun>Command / Query: <Verb><Noun>Query / Result: <Verb><Noun>Result
    → ErrorMessage enum: <Domain>ErrorMessage
```

---

## STEP 2 — The Domain layer

**Related docs**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/tactical-ddd.md](architecture/tactical-ddd.md) · [architecture/domain-service.md](architecture/domain-service.md) · [architecture/aggregate-id.md](architecture/aggregate-id.md)

```
[ ] Does the domain/ directory have the Aggregate Root, Entity, Value Object, Domain Event, and Repository interface?
[ ] Are business rules and invariants encapsulated in the Aggregate Root?
    → if the Application Service has business logic, move it into the Aggregate
[ ] Does a Domain-layer file have a framework dependency? (@Injectable, @Module, etc.)
    → if so, remove it. The Domain layer is framework-independent
[ ] Does a Domain-layer file have an ORM-related import?
    → if so, remove it. Use it only in the Infrastructure layer
[ ] Is the Repository interface defined as an abstract class?
[ ] Is the Repository interface located in the domain/ layer?
[ ] Do Aggregates reference each other only by ID, with no direct reference?
[ ] Is there code that changes an Aggregate's internal state directly from outside it?
    → fix it to change state only through an Aggregate Root method
[ ] Does the Value Object implement attribute-based equality comparison?
[ ] Is the Aggregate's ID generated as a UUID v4 (hyphens stripped, 32-char hex) at creation?
    → use generateId() on new creation, and reuse the existing ID as-is on DB restoration
```

---

## STEP 3 — Layer architecture

**Related docs**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](architecture/cqrs-pattern.md) · [architecture/cross-domain-communication.md](architecture/cross-domain-communication.md)

```
[ ] Does the Controller do anything besides calling the Service and converting errors?
    → if so, move it into the Service
[ ] Does the Application Service carry out business logic directly? (checking a state-change condition, doing a calculation, etc.)
    → if so, move it into a domain method on the Aggregate
[ ] Does the Service throw an HTTP exception (HttpException, NotFoundException, etc.)?
    → replace it with a plain Error. HTTP conversion happens only in the Interface layer
[ ] If the Application layer has a write use case, do the command/ directory and a Command object exist?
[ ] If the Application layer has a read use case, do the query/ directory and a Query/Result object exist?
[ ] Does the Command Service use only the Repository, and the Query Service only the Query interface?
    → fix it if the Command Service uses the Query interface directly, or if there's a reverse dependency
[ ] Is the Query interface defined as an abstract class in application/query/?
[ ] Is the Query implementation located in infrastructure/?
[ ] Does the Interface DTO wrap the Application Query/Result/Command via extends?
    → if the Interface DTO has extra logic or fields, move them into the Application layer
[ ] Is the layer dependency direction correct? (Interface → Application → Domain ← Infrastructure)
    → fix it if a lower layer imports a higher layer
[ ] Are events created only inside an Aggregate's own domain method?
    → the Command Service never creates an event directly
[ ] Does the Repository implementation's save method save domainEvents to the outbox together?
[ ] Is something that needs to notify an external BC converted into an Integration Event before publishing?
    → never pass a Domain Event object to the outside as-is
```

---

## STEP 4 — The Repository pattern

**Related docs**: [architecture/repository-pattern.md](architecture/repository-pattern.md)

```
[ ] Is the Repository defined per Aggregate Root (not per table/Entity)?
[ ] Is the Repository interface (an abstract class) in the domain/ layer?
[ ] Is the Repository implementation in the infrastructure/ layer?
[ ] Do the Repository method names follow the find<Noun>s / save<Noun> / delete<Noun> pattern?
[ ] Does the Repository have an update<Noun> method?
    → if so, remove it. Look it up, modify it via an Aggregate domain method, then save it via save<Noun>
[ ] Was a separate findOne / findById method made for a single-record lookup?
    → if so, remove it. Use the take: 1 + .then(r => r.<noun>s.pop()) pattern in the Service
[ ] Is the key name in the Repository's find-method return type the domain object's name, pluralized?
    → correct example: { orders: Order[]; count: number }
    → wrong example: { items: Order[]; count: number }, { result: Order[] }
[ ] Does the Repository implementation convert a DB record into a domain Aggregate object?
    → convert it via new Aggregate(row) instead of returning the raw DB row
```

---

## STEP 5 — Error handling

**Related docs**: [architecture/error-handling.md](architecture/error-handling.md)

```
[ ] Does conversion to an HTTP exception happen only in the Interface layer (the Controller)?
    → Domain/Application only ever throw a plain Error
[ ] Is the error message typed as an enum?
    → never throw a free-form string directly
[ ] Is the error-message enum defined with the key = value pattern?
    → used as throw new Error(ErrorMessage['...'])
[ ] Is the error-response format consistent? (includes the statusCode, code, message, error fields)
[ ] Does Domain/Application use throw new HttpException / NotFoundException, etc.?
    → if so, replace it with throw new Error(ErrorMessage['...'])
```

---

## STEP 6 — REST API endpoints

**Related docs**: [conventions.md](conventions.md) · [architecture/api-response.md](architecture/api-response.md)

```
[ ] Is the URL built from a plural-noun resource, not a verb?
    → correct example: GET /orders, POST /orders
    → wrong example: GET /getOrders, POST /createOrder
[ ] Is the resource name plural?
    → correct example: /orders, /users / wrong example: /order, /user
[ ] Does the URL use only lowercase kebab-case?
[ ] Is the HTTP method used correctly?
    → GET: lookup, POST: create, PUT: full update, PATCH: partial update, DELETE: delete
[ ] Does the response code match the HTTP method?
    → GET/PUT/PATCH: 200, POST: 201, DELETE: 204
[ ] Is a non-CRUD action expressed as a sub-resource path?
    → correct example: POST /orders/:orderId/cancel
[ ] Is the list-response key the domain object's name, pluralized?
    → correct example: { orders: [...], count: 10 } / wrong example: { data: [...] }
[ ] Does the URL have no trailing slash (/) and no file extension (.json)?
[ ] Does every endpoint have machine-readable API documentation (OpenAPI/Swagger) with a summary/description?
    → wrong example: an operation with only an operationId/route registered and no summary or description
[ ] Is every non-2xx response the endpoint can actually produce documented (not just the success response)?
    → cross-check against the endpoint's own error-mapping table — an undocumented 404/409/etc. is a gap, not a style nit
```

---

## STEP 7 — Transactions / idempotency

**Related docs**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/domain-events.md](architecture/domain-events.md)

```
[ ] Is a write operation spanning multiple Repositories wrapped in a single transaction?
    → a transaction is required if a Command Service calls 2 or more Repositories
[ ] Is there an unnecessary transaction on a Command that calls only a single Repository?
[ ] Are saving the Aggregate and saving the Domain Event to the outbox wrapped in the same transaction?
[ ] Does enqueuing a Task happen inside the Command transaction? (blocks the dual-write problem)
[ ] Is the event handler implemented idempotently?
    → since delivery is at-least-once, the result must be the same on a duplicate delivery
[ ] Does the Scheduler (@Cron) only call TaskQueue.enqueue, never running business logic directly?
[ ] Does the Task Controller throw the error as-is?
    → the catch-plus-error-conversion pattern is forbidden. Swallowing the exception loses the failure
[ ] Is a DLQ set up on every Task queue?
```

---

## STEP 8 — Observability / configuration

**Related docs**: [architecture/observability.md](architecture/observability.md) · [architecture/config.md](architecture/config.md)

```
[ ] Are logs structured? (key-value JSON, snake_case field names)
[ ] Does the Domain layer never log?
    → logging happens only in the Application layer or above
[ ] Is a Correlation ID propagated on every request?
[ ] Are required environment variables validated at startup, with immediate termination on failure? (fail-fast)
[ ] Is no sensitive value (a DB password, a JWT secret, an API key) hardcoded in the code?
    → use Secrets Manager in production
[ ] Is configuration managed in files split per concern?
```

---

## STEP 9 — Testing patterns

**Related docs**: [architecture/testing.md](architecture/testing.md)

```
[ ] Are Domain-layer unit tests written as pure code, with no framework?
    → test by calling new Aggregate() directly
[ ] Does the Application Service test replace the Repository with a mock?
[ ] Does the E2E/integration test verify the use-case flow via a real HTTP request?
[ ] Does the E2E/integration test use an in-memory DB or testcontainers?
    → never connect directly to the production DB
[ ] Is there a test for an Aggregate-invariant violation?
[ ] Is there a test verifying whether a Domain Event was published?
[ ] Does test naming follow the {domain action}_when_{condition}_then_{expected result} pattern?
```

---

## STEP 10 — Final overall-consistency check

**Related docs**: [conventions.md](conventions.md)

```
[ ] Is a newly added file included in that layer's registration structure?
[ ] Is there no leftover TODO, console.log, or temporary comment in the code you worked on?
[ ] Is the Ubiquitous Language reflected consistently in the code (class names, method names, variable names)?
[ ] Is logger output in a structured form? (snake_case field names)
[ ] Does the commit message follow the Conventional Commits format? (feat/fix/refactor + scope)
[ ] Is the commit message's description descriptive, with no trailing period?
[ ] Is the branch name kebab-case, branched off of main?
[ ] Does the change land via a PR, with no direct commit/push to the main branch?
```

---

## STEP 11 — Design-deliverable shape (for design-phase work)

**Related docs**: [development-process.md](development-process.md)

> This applies only when a design-phase (RA, SD, DM, TD) deliverable was written.

```
[ ] RA deliverable: does a functional requirement include an FR-### number, a description, acceptance criteria, and a priority (MoSCoW)?
[ ] RA deliverable: does a use case include a UC-### number, the Actor, preconditions, the main flow, and exception flows?
[ ] SD deliverable: does the subdomain classification table include the type (Core/Supporting/Generic) and the implementation strategy?
[ ] SD deliverable: does the Context Map include the relationship type and the reason it was chosen?
[ ] DM deliverable: does the Event Storming result include Actor/Command/Aggregate/Event/Policy columns?
[ ] DM deliverable: does the Ubiquitous Language glossary include the term/definition/owning Context?
[ ] DM deliverable: do the business rules/invariants include an INV-### number and how a violation is handled?
[ ] TD deliverable: does the file-structure tree include the 4 layers domain/application/infrastructure/interface?
[ ] TD deliverable: does the Repository interface spec include find<Noun>s/save<Noun>/delete<Noun>?
[ ] TD deliverable: does the Application Service spec include the processing flow/transaction scope?
[ ] IM deliverable: is it proceeding via Vertical Slicing (implementing per use case)?
```

---

## How to use this checklist

1. Go through **STEP 1 through 10 in order**.
2. When you find a violation, **fix the file immediately** and check it off.
3. After fixing it, **confirm nothing related was affected** in other files.
4. If this was design-phase work, also go through **STEP 11**.
5. Wrap up the task once every check is done.

> If an item's intent is unclear, refer to the related doc.
> See `implementations/<lang>/docs/checklist.md` for additional per-framework verification items. (`docs/implementations/` is a coverage-audit report between the root principles and each language's docs, not a checklist supplement.)
