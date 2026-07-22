# Backend Service Playbook

A guide to **framework-agnostic** design/implementation principles for DDD-based backend services.
It doesn't depend on any specific language or framework. The code examples use TypeScript, but the patterns themselves apply the same way in any language.

See `implementations/<lang>/` for the implementation detail of each language/framework.

## Docs to consult when working

### Design / process

| Task / keyword | Doc to read |
|---|---|
| Development process, agent roles, design deliverables, Vertical Slicing | `docs/development-process.md` |
| Self-review after finishing a task, the checklist | `docs/checklist.md` |
| The harness's design principles, what an evaluator may/may not check | `docs/harness.md` |
| The AI-agent architecture-compliance benchmark, reusing the harness as a scorer | `docs/benchmark.md` |
| Automatically checking whether a doc no longer matches the real code (stale docs) | `docs/docs-drift-check.md` |
| Directory structure, file naming, per-layer file placement | `docs/architecture/directory-structure.md` |

### Strategic design

| Task / keyword | Doc to read |
|---|---|
| Strategic design, Subdomain, Bounded Context, Context Map, the Ubiquitous Language | `docs/architecture/strategic-ddd.md` |
| Choosing a communication pattern between BCs, sync vs. async, ACL | `docs/architecture/cross-domain-communication.md` |

### Tactical design

| Task / keyword | Doc to read |
|---|---|
| Aggregate, Entity, Value Object, Domain Event, boundary criteria | `docs/architecture/tactical-ddd.md` |
| Aggregate ID generation, UUID, ID assignment in the Domain layer | `docs/architecture/aggregate-id.md` |
| Layer roles, Domain / Application / Interface / Infrastructure | `docs/architecture/layer-architecture.md` |
| The Repository interface/implementation, abstract classes | `docs/architecture/repository-pattern.md` |
| Transaction propagation (Unit of Work), common Entity columns, soft delete, migrations | `docs/architecture/persistence.md` |
| Domain Service, coordinating multiple Aggregates, domain judgment logic | `docs/architecture/domain-service.md` |
| Technical Service, abstracting a technical-infrastructure service, separating encryption/an external API client | `docs/architecture/domain-service.md` |
| Domain Event, the Outbox pattern, Integration Event, idempotency | `docs/architecture/domain-events.md` |
| CQRS, Command/Query separation, the Handler pattern | `docs/architecture/cqrs-pattern.md` |

### Quality / interface

| Task / keyword | Doc to read |
|---|---|
| Error-handling principles, typing error messages, the error-response format | `docs/architecture/error-handling.md` |
| API response structure, pagination, list/single-record response format | `docs/architecture/api-response.md` |
| Testing strategy, Domain unit tests, Application mock tests, E2E | `docs/architecture/testing.md` |
| Commit messages, branch naming, REST API design, rate limiting, method-naming principles | `docs/conventions.md` |

### Authentication / cross-cutting concerns

| Task / keyword | Doc to read |
|---|---|
| JWT authentication, Bearer tokens, the token issue/verify flow | `docs/architecture/authentication.md` |
| The request pipeline, Correlation ID, where cross-cutting concerns are handled | `docs/architecture/cross-cutting-concerns.md` |

### Async / scheduling

| Task / keyword | Doc to read |
|---|---|
| Scheduling, Cron, Task Queue, batch jobs, safety with multiple instances | `docs/architecture/scheduling.md` |
| The Task Outbox pattern, blocking dual-write, Task idempotency | `docs/architecture/scheduling.md` |

### Operations / infrastructure

| Task / keyword | Doc to read |
|---|---|
| Log-level policy, structured logs, Correlation ID, metrics/tracing | `docs/architecture/observability.md` |
| Graceful shutdown, SIGTERM, liveness/readiness probes | `docs/architecture/graceful-shutdown.md` |
| Dockerfile, multi-stage builds, container-image principles | `docs/architecture/container.md` |
| Env-var validation, fail-fast, splitting config, when to use Secrets Manager | `docs/architecture/config.md` |
| Secret management, looking up/caching from Secrets Manager, a TTL cache | `docs/architecture/secret-manager.md` |
| Local dev environment, docker-compose, LocalStack | `docs/architecture/local-dev.md` |
| File upload/download, presigned URLs, storage integration | `docs/architecture/file-storage.md` |

### Implementations

| Framework | Doc location |
|---|---|
| NestJS (TypeScript) | `implementations/nestjs/` — see `CLAUDE.md` |
| Go | `implementations/go/docs/` |
| Spring Boot (Java) | `implementations/java-springboot/docs/` |
| Kotlin Spring Boot | `implementations/kotlin-springboot/docs/` |
| FastAPI (Python) | `implementations/fastapi/docs/` |

## Verifying an implementation

Use each implementation directory's `harness.sh` to check for structure/placement rule violations.

```bash
bash implementations/<lang>/harness.sh <projectRoot>
```

See `docs/checklist.md` for the checklist-based self-review. See `docs/harness.md` for the harness's own design principles (it only evaluates compliance with architectural rules, not business-domain knowledge).
