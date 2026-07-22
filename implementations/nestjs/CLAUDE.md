# NestJS Implementation Guide

This is the design/implementation guide for a DDD-based NestJS TypeScript server project.
It follows the 4-layer structure `src/<domain>/{domain,application,interface,infrastructure}/`.

> For **framework-agnostic design principles**, see the root [CLAUDE.md](../../CLAUDE.md) and `docs/architecture/`.
> This document focuses on NestJS implementation details.

## Documents to reference while working

### Design / Process

| Task / Keyword | Document to read |
|---------------|----------|
| Design, requirements analysis, tactical design, design deliverables | `../../docs/development-process.md` (root shared document) |
| Modifying legacy features, Vertical Slice refactoring | `../../docs/development-process.md` (Modifying Legacy Features section, root shared document) |
| Design principles | `docs/architecture/design-principles.md` |
| Strategic design, Subdomain, Bounded Context, Context Map, Ubiquitous Language | `../../docs/architecture/strategic-ddd.md` (root shared document) |
| Coding conventions, file naming rules, import rules | `docs/conventions.md` |
| Adding a new domain, domain module template, Order example | `docs/reference.md` — `scripts/create-domain.js` can instantly generate the `docs/reference.md` template as real code (see "Scaffolding" below) |
| Self-review after finishing work, checklist | `docs/checklist.md` |
| Marking deprecated endpoints, @ApiOperation deprecated | `docs/conventions.md` (Deprecated Endpoints section) |

### Layers / Structure

| Task / Keyword | Document to read |
|---------------|----------|
| Project structure, directory layout | `docs/architecture/directory-structure.md` |
| Layer responsibilities, Domain / Application / Interface / Infrastructure | `docs/architecture/layer-architecture.md` |
| Modeling Aggregate, Entity, Value Object, Domain Event (Money, Account examples) | `docs/architecture/tactical-ddd.md` |
| Repository interface/implementation, abstract class | `docs/architecture/repository-pattern.md` |
| Domain Service, Technical Service (coordinating multiple Aggregates, separating technical infrastructure services) | `../../docs/architecture/domain-service.md` (root shared document) |
| Cross-domain calls, Adapter pattern (NestJS implementation details) | `docs/architecture/cross-domain.md` |
| Choosing inter-BC communication patterns, sync vs async, ACL, Context Map | `../../docs/architecture/cross-domain-communication.md` (root shared document) |
| Aggregate ID generation/propagation | `docs/architecture/aggregate-id.md` |
| Shared module structure, common infrastructure modules | `docs/architecture/shared-modules.md` |
| Module composition, @Module, providers, controllers, DI bindings | `docs/architecture/module-pattern.md` |

### Data / Transactions

| Task / Keyword | Document to read |
|---------------|----------|
| DB queries, TypeORM usage, QueryBuilder | `docs/architecture/persistence.md` |
| Transaction management, TransactionManager, AsyncLocalStorage | `docs/architecture/persistence.md` |
| Migrations, synchronize, data-source | `docs/architecture/persistence.md` |
| Domain Event, event publishing/subscription, fan-out | `docs/architecture/domain-events.md` |
| Integration Event, cross-BC events, public contract, event versioning | `docs/architecture/domain-events.md` |
| Outbox pattern, OutboxWriter, OutboxPoller, OutboxConsumer, EventHandlerRegistry | `docs/architecture/domain-events.md` |
| @nestjs/cqrs, CommandBus, QueryBus, CommandHandler | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| Task / Keyword | Document to read |
|---------------|----------|
| REST endpoints, Controller, DTO | `docs/architecture/module-pattern.md` |
| Swagger documentation, @ApiProperty, @ApiOperation, @ApiTags | `docs/architecture/module-pattern.md` |
| Pagination, common response format, page/take | `docs/architecture/api-response.md` |
| Rate Limiting, Throttler | `docs/architecture/rate-limiting.md` |
| Authentication, authorization, Auth Guard, JWT, Bearer token | `docs/architecture/authentication.md` |
| Middleware / Guard / Interceptor / Pipe | `docs/architecture/cross-cutting-concerns.md` |
| Error handling, generateErrorResponse, error message enum | `docs/architecture/error-handling.md` |

### Operations / Infrastructure

| Task / Keyword | Document to read |
|---------------|----------|
| Environment configuration, environment variable validation, ConfigModule | `docs/architecture/config.md` |
| Secret management, secret injection, secret-manager | `docs/architecture/secret-manager.md` |
| App bootstrap, main.ts, NestFactory, Swagger setup | `docs/architecture/bootstrap.md` |
| Graceful Shutdown, OnApplicationShutdown | `docs/architecture/graceful-shutdown.md` |
| Local development environment, docker-compose, LocalStack, Postgres | `docs/architecture/local-dev.md` |
| Dockerfile, multi-stage build | `docs/architecture/container.md` |
| Logging, log levels, structured log | `docs/architecture/observability.md` |
| File upload/download, Presigned URL, S3 | `docs/architecture/file-storage.md` |

### Async / Task Queue / Scheduling

| Task / Keyword | Document to read |
|---------------|----------|
| Scheduling, @Cron, @Interval, ScheduleModule | `docs/architecture/scheduling.md` |
| Task Queue, @TaskConsumer, Task Controller, Outbox | `docs/architecture/scheduling.md` |
| SQS, FIFO, MessageDeduplicationId, DLQ | `docs/architecture/scheduling.md` |

### Quality / Verification

| Task / Keyword | Document to read |
|---------------|----------|
| Testing, unit tests, integration tests, jest configuration | `docs/architecture/testing.md` |
| Running the harness, list of evaluator rules | `harness/README.md` |
| Harness design principles (evaluates only architecture rules, not business domain knowledge) | `../../docs/harness.md` (root shared document) |

## Implementation Verification

```bash
./harness.sh <projectRoot>
```

FAIL items' `ruleId` and message include links to the relevant document. Open that document to fix the issue.

## Scaffolding — new domain generator

This script turns the "Reference Implementation Template" (Order example) in `docs/reference.md`
into real code that passes the entire harness (31 evaluators) at grade A (100/100), then
generalizes it by parameterizing only the domain name so it can be reused. It generates, in
one pass, the Aggregate (single state field + PENDING/ACTIVE/CANCELLED) + CQRS
CommandHandler/QueryHandler (CommandBus/QueryBus) + one domain event + Repository +
Controller + DTO + Module. Outbox draining is handled not by a per-domain Relay but by the
shared `outbox/` module (OutboxPoller/OutboxConsumer), so the generated Module's
`onModuleInit()` registers its own domain's event handlers with the shared
`EventHandlerRegistry` (see `domain-events.md`).

```bash
# Default: generates under ../examples/src/<domain>/, leaves app-module.ts untouched and
# only prints the import/registration snippet to paste in
node scripts/create-domain.js Coupon

# Passing --wire also auto-inserts the import/registration into app-module.ts
node scripts/create-domain.js Coupon --wire

# To generate into a different project (e.g. one cloned from this repo as a template), specify --out
node scripts/create-domain.js Coupon --out /path/to/other-project/src --wire
```

Verify immediately after generation with `./harness.sh <projectRoot>` — this has been tested
for real with new domains unrelated to Account/Card (including multi-word/irregular-plural
names like Coupon, LoyaltyCategory), confirming 100/100. Because it uses a naive pluralization
rule (+s/+es/y→ies), domains with irregular plurals may need the generated names — e.g.
`find<Domains>`/`<domains>` — manually adjusted. What's generated is a structural skeleton
(an empty CRUD-style starting point), so the actual business rules, error messages, and
fields need to be filled in yourself after generation.

## Example Code

The `examples/` directory contains a full example implementation of the Account domain.
Use it as a template when working on a new domain (just change the domain name).
