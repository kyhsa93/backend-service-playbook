# Kotlin Spring Boot Implementation Guide

This is the design/implementation guide for a DDD-based Kotlin Spring Boot server project.
It follows the 4-layer structure `<domain>/{domain,application,infrastructure,interfaces}/`.

> **Design principles (framework-agnostic)** are covered in the root [CLAUDE.md](../../CLAUDE.md) and `../../docs/architecture/`.
> This document focuses on Kotlin Spring Boot implementation details. Java Spring Boot and the Spring framework mechanisms themselves overlap significantly, but each document is rewritten using Kotlin idioms (null-safety, `data class`, `sealed class`, `companion object`, etc.) — it is not a direct port of the Java documents.

## Documents to reference while working

### Layer / Structure

| Task / keyword | Document to read |
|---------------|----------|
| Project structure, package layout, actual structure of the Account/notification modules | `docs/architecture/directory-structure.md` |
| App bootstrap, `AccountServiceApplication.kt`, `runApplication`, `application.yml` load order, introducing Swagger/CORS | `docs/architecture/bootstrap.md` |
| Layer responsibilities, Domain / Application / Infrastructure / Interfaces, null-safety | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, `data class`, `sealed class`, `companion object` factories | `docs/architecture/tactical-ddd.md` |
| Repository interface/implementation, method naming (`find<Noun>s`/`save<Noun>`/`delete<Noun>`) | `docs/architecture/repository-pattern.md` |
| Domain Service, Technical Service (notification module example) | `../../docs/architecture/domain-service.md` (root shared document) |
| Aggregate ID generation rules (32-digit hex, hyphens removed) | `docs/architecture/aggregate-id.md` |
| Strategic design, Subdomain, Bounded Context, Context Map, ubiquitous language | `../../docs/architecture/strategic-ddd.md` (root shared document) |
| Spring DI container mechanism, `@Component`/`@Service`/`@Repository`/`@Configuration`, `@Bean`, avoiding circular dependencies | `docs/architecture/module-pattern.md` |
| Summary of core design principles (cheat sheet), this implementation's 10-15 rules | `docs/architecture/design-principles.md` |
| Placement of shared/common code, `common/`/`config/`/`auth/`/`outbox/` package conventions | `docs/architecture/shared-modules.md` |

### Data / Transactions

| Task / keyword | Document to read |
|---------------|----------|
| `@Transactional`, transaction propagation, Soft Delete, migrations (Flyway) | `docs/architecture/persistence.md` |
| Domain Event, Outbox pattern, `OutboxWriter`/`OutboxPoller`/`OutboxConsumer` | `docs/architecture/domain-events.md` |
| Command/Query Service separation, criteria for switching to Handler-based CQRS | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| Task / keyword | Document to read |
|---------------|----------|
| REST endpoints, `@RestController`, DTOs (`data class`) | `docs/architecture/layer-architecture.md` |
| API response format, pagination, `page`/`take` | `docs/architecture/api-response.md` |
| API documentation, Swagger/OpenAPI, `springdoc-openapi`, `@Operation`/`@Schema` | `docs/architecture/bootstrap.md` ("OpenAPI/Swagger" section), `docs/conventions.md` (section 8) |
| Authentication, JWT, Bearer token, Spring Security Filter | `docs/architecture/authentication.md` |
| Filter / HandlerInterceptor, Correlation ID | `docs/architecture/cross-cutting-concerns.md` |
| Error handling, `sealed class` exception hierarchy, `@RestControllerAdvice`, error response format | `docs/architecture/error-handling.md` |
| Cross-domain calls, Adapter pattern implementation (hypothetical example), calling the User BC | `docs/architecture/cross-domain.md` |
| Rate Limiting, Resilience4j `RateLimiter`, request-rate-limiting Filter | `docs/architecture/rate-limiting.md` |

### Operations / Infrastructure

| Task / keyword | Document to read |
|---------------|----------|
| Environment configuration, `@ConfigurationProperties`, fail-fast validation | `docs/architecture/config.md` |
| Secret management, AWS Secrets Manager, TTL cache | `docs/architecture/secret-manager.md` |
| Dockerfile, multi-stage build, JRE base image | `docs/architecture/container.md` |
| Graceful Shutdown, `server.shutdown: graceful`, Actuator probes | `docs/architecture/graceful-shutdown.md` |
| Local development environment, docker-compose, LocalStack (SES) | `docs/architecture/local-dev.md` |
| Structured logging, MDC, Correlation ID, `kotlin-logging` | `docs/architecture/observability.md` |
| File upload/download, presigned URLs, AWS SDK v2 | `docs/architecture/file-storage.md` |

### Async / Scheduling

| Task / keyword | Document to read |
|---------------|----------|
| `@Scheduled`, Task Queue (taskqueue/), Outbox Poller/Consumer, why coroutines aren't used | `docs/architecture/scheduling.md` |

### Quality / Verification

| Task / keyword | Document to read |
|---------------|----------|
| Testing, the Domain/Application/E2E 3 layers, MockK, Testcontainers | `docs/architecture/testing.md` |
| Running the harness, list of check rules | `harness/README.md` |
| Harness design principles (evaluates architectural rules only, not business domain knowledge) | `../../docs/harness.md` (root shared document) |
| Lint/code style, running ktlint | "Lint" section below |

## Implementation verification

```bash
./harness.sh <projectRoot>
```

Each FAIL item's rule name and message include a link to the relevant document. Open that document and fix the issue.

## Lint

The [ktlint](https://pinterest.github.io/ktlint/) Gradle plugin (`org.jlleitschuh.gradle.ktlint`) is configured in `examples/`. The harness only evaluates DDD architectural rules (file placement, layering), so general code style violations like import cleanup and formatting are handled by ktlint.

```bash
cd examples
./gradlew ktlintFormat   # auto-fix (most formatting violations)
./gradlew ktlintCheck    # check only (prints violations without fixing)
```

`ktlintCheck` is also wired into the `check`/`build` tasks, so it runs automatically as part of `./gradlew build`. CI (`kotlin-springboot.yml`) also runs it as a separate step first, to fail fast on style violations.

Use the default ruleset as-is, avoiding maximum strictness (see the `ktlint { }` block in `build.gradle.kts`). For violations that can't be auto-fixed (wildcard imports, filename mismatches for single-class-per-file, etc.), fix the actual code/filename rather than hiding them with `@Suppress` — if an exception is truly needed, document why, similar to the "Note" footnotes in `docs/conventions.md`.

## Scaffolding — new domain generator

This is a script that was built by combining the actual code of Account (Repository/Outbox pattern) and
Card (2nd domain, domain/JPA-separated structure) into the practical implementation template defined by
`docs/reference.md`, then generalized to be reusable by parameterizing only the domain name. In one shot
it generates a single `status` field (PENDING/ACTIVE/CANCELLED) + an Aggregate with
`create()`/`activate()`/`cancel(reason)` (including a sealed exception hierarchy) + Command/Query
"Service" (CQRS, plain `@Service` classes — not Handler/CommandBus) + one domain event (published only
by `cancel()`) + Repository + JPA Entity/Mapper + REST Controller/DTO + Flyway migration.

It's written as a Python script so it doesn't require building the Kotlin/Gradle toolchain (runs instantly without compilation).

```bash
# Default: only generates under ../examples/src/main/kotlin/com/example/accountservice/<domain>/.
# Does not touch EventHandlerRegistry.kt/GlobalExceptionHandler.kt — only prints the snippets to paste in.
python3 scripts/create_domain.py Coupon

# To generate into a different project (e.g. a scratch copy), specify the Gradle module root with
# --project-root, and add --wire if you also want EventHandlerRegistry.kt/GlobalExceptionHandler.kt
# auto-patched.
python3 scripts/create_domain.py LoyaltyCategory --project-root /path/to/scratch-project/examples --wire
```

**There is (mostly) no module-registration step** — unlike nestjs (`@Module({ providers: [...] })`) or
Go, Spring automatically collects classes annotated with `@Service`/`@Component`/`@Repository`/
`@RestController` across the whole classpath (component scanning — verified directly that
`AccountServiceApplication.kt` has no place that lists domain beans either). **However, unlike
java-springboot, kotlin-springboot is not completely free of this here** — java-springboot's
`OutboxConsumer` auto-collects implementations via constructor-injected `List<OutboxEventHandler>`, but
this repository's `outbox/EventHandlerRegistry.kt` explicitly injects each Domain Event handler as an
individual constructor parameter and adds the handler entry directly into the `Map<eventType, handler>`
literal built immediately in that constructor (domain-events.md step 3, confirmed against the actual
code). `common/GlobalExceptionHandler.kt` similarly requires hand-registering an `@ExceptionHandler`
method for each domain exception. So these two files are the only ones that actually need to be edited
for every new domain, and the `--wire` option automates that patch (inserting the import then
re-sorting + adding the constructor parameter + adding the `Map` literal/`@ExceptionHandler` entry).
Running without `--wire` only prints what to paste into the two files to the console — you might not
want the script arbitrarily modifying existing files, so the default is the safe option.

Verification steps right after generation (`<projectRoot>` is `examples` if using the default, or the
path you gave `--project-root`):

```bash
bash implementations/kotlin-springboot/harness.sh <projectRoot>
cd <projectRoot> && ./gradlew ktlintCheck && ./gradlew build   # build already includes ktlintCheck, so this is effectively redundant
```

Actually generated new domains unrelated to Account/Card (single-word "Coupon" and multi-word
"LoyaltyCategory") and confirmed 0 harness FAILs (all 309 PASS), `ktlintCheck` passing, and
`./gradlew build` (including Testcontainers e2e) succeeding. Since it uses a naive pluralization rule
(+s/+es/consonant+y→ies), for domains with irregular plurals (e.g. person → people), generated names
like `find<Domains>`/`<domains>`/REST paths may need manual adjustment — the generator's output guides
you on this. Because ktlint (`standard:class-signature`, `standard:argument-list-wrapping`,
`standard:max-line-length`) judges only by the actually rendered line length, the script computes the
length at generation time and branches accordingly for parts whose single-line/multi-line form depends
on domain name length (the sealed exception's supertype call, Repository query branches, etc.). What's
generated is a structural skeleton (an empty CRUD-style starting point) — the actual business rules,
error messages, and fields are filled in by hand after generation.

## Example code (full Account domain)

The `examples/` directory contains a full example implementation of the Account domain (account opening/deposit-withdrawal/suspend/reactivate/close + SES notifications).
Use this as a template when working on a new domain (only the domain name changes).

**Patterns implemented in `examples/`**: Aggregate ID hyphen removal, authentication (JWT/Spring Security), Flyway migrations, 3-layer testing (Domain/Application/E2E), structured logging/Correlation ID, Dockerfile, Secrets Manager integration, `@ConfigurationProperties` + `@Validated` fail-fast validation for jwt.secret (`JwtProperties`), Graceful Shutdown/Actuator liveness·readiness probes (`server.shutdown: graceful` + `/actuator/health/liveness`·`/actuator/health/readiness`), Rate Limiting (Resilience4j `RateLimiter`, `RateLimitingFilter`), Soft Delete wiring, the 4-field error response structure + global exception handling, separating the Query Service's read-only Repository, and Repository query/save method naming (`AccountRepository`'s `findAccounts`/`saveAccount`/`deleteAccount`, [repository-pattern.md](docs/architecture/repository-pattern.md)) — all reflected in the actual code. Each document (`aggregate-id.md`, `authentication.md`, `persistence.md`, `testing.md`, `observability.md`, `container.md`, `secret-manager.md`, `config.md`, `graceful-shutdown.md`, `rate-limiting.md`, `repository-pattern.md`) specifies this against the real code.

Outbox-based events are implemented in `outbox/`, `account/application/event/*EventHandler.kt`. The Task Queue (periodic interest payments, monthly card statement delivery) is implemented in `taskqueue/` + `account/infrastructure/scheduling/InterestPaymentScheduler.kt`/`card/infrastructure/scheduling/CardStatementScheduler.kt` + `account/interfaces/task/`/`card/interfaces/task/` (see `docs/architecture/scheduling.md`). When writing new code, follow the correct pattern defined by each document, not necessarily the current pattern in `examples/`.
