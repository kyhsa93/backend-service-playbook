# Spring Boot Implementation Guide

This is the design/implementation guide for a DDD-based Java Spring Boot server project.
It follows the 4-layer structure `<domain>/{domain,application,infrastructure,interfaces}/`.

> **Design principles (framework-agnostic)** are covered in the root [CLAUDE.md](../../CLAUDE.md) and `../../docs/architecture/`.
> This document focuses on Java Spring Boot implementation details. While Kotlin Spring Boot and the Spring framework mechanics themselves overlap significantly, each document is rewritten in Java idiom (records, Lombok `@RequiredArgsConstructor`, the plural `interfaces` package, etc.) — it is not a direct port of the Kotlin document.

## Documents to reference while working

### Layers / structure

| Task / keyword | Document to read |
|---------------|----------|
| Project structure, package layout, actual structure of the Account/notification modules | `docs/architecture/directory-structure.md` |
| Layer responsibilities, Domain / Application / Infrastructure / Interfaces, separation of Domain/JPA mapping | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, `record`, static factory methods | `docs/architecture/tactical-ddd.md` |
| Repository interface/implementation, method naming (`find<Noun>s`/`save<Noun>`/`delete<Noun>`) | `docs/architecture/repository-pattern.md` |
| Aggregate ID generation rules (32-character hex, hyphens removed) | `docs/architecture/aggregate-id.md` |

### Data / transactions

| Task / keyword | Document to read |
|---------------|----------|
| `@Transactional`, transaction propagation, `REQUIRES_NEW`, soft delete, migrations (Flyway) | `docs/architecture/persistence.md` |
| Domain Events, the Outbox pattern, `OutboxWriter`/`OutboxPoller`/`OutboxConsumer` | `docs/architecture/domain-events.md` |
| Command/Query Service separation, criteria for switching to Handler-based CQRS, read-only Query interfaces | `docs/architecture/cqrs-pattern.md` |
| Domain Service, coordinating multiple Aggregates (`RefundEligibilityService`), difference from Technical Service | `docs/architecture/domain-service.md` |

### API / Interface

| Task / keyword | Document to read |
|---------------|----------|
| REST endpoints, `@RestController`, Interface DTOs (`record`) | `docs/architecture/layer-architecture.md` |
| API response format, pagination, `page`/`take` | `docs/architecture/api-response.md` |
| API documentation, Swagger/OpenAPI, springdoc, `@Operation`/`@ApiResponse`/`@Schema` | `docs/architecture/api-response.md` (completeness bar), `docs/architecture/bootstrap.md` (springdoc setup, `OpenApiConfig`) |
| Authentication, JWT, Bearer tokens, Spring Security `SecurityFilterChain` | `docs/architecture/authentication.md` |
| `Filter`/`HandlerInterceptor`, correlation ID, MDC | `docs/architecture/cross-cutting-concerns.md` |
| Error handling, `AccountException`, `@ExceptionHandler`, the 4-field error response format | `docs/architecture/error-handling.md` |

### Operations / infrastructure

| Task / keyword | Document to read |
|---------------|----------|
| Environment configuration, `@ConfigurationProperties`, fail-fast validation | `docs/architecture/config.md` |
| Secret management, AWS Secrets Manager, TTL cache | `docs/architecture/secret-manager.md` |
| Dockerfile, multi-stage builds, layered JAR, JRE base image | `docs/architecture/container.md` |
| Graceful shutdown, `server.shutdown: graceful`, Actuator probes | `docs/architecture/graceful-shutdown.md` |
| Local development environment, docker-compose, LocalStack (SES) | `docs/architecture/local-dev.md` |
| Structured logging, MDC, correlation ID, Logback JSON encoder | `docs/architecture/observability.md` |
| File upload/download, presigned URLs, AWS SDK v2 | `docs/architecture/file-storage.md` |

### Async / scheduling

| Task / keyword | Document to read |
|---------------|----------|
| `@Scheduled`, `@EnableScheduling`, Outbox Relay/Consumer, multi-instance safety | `docs/architecture/scheduling.md` |

### Quality / verification

| Task / keyword | Document to read |
|---------------|----------|
| Testing, the three Domain/Application/E2E layers, Mockito, Testcontainers | `docs/architecture/testing.md` |
| Running the harness, list of check rules | `harness/README.md` |
| Harness design principles (evaluates architectural rules only, not business-domain knowledge) | `../../docs/harness.md` (root shared document) |

### Bonus documents — no counterpart in root (6 extra compared to NestJS)

| Task / keyword | Document to read |
|---------------|----------|
| `AccountServiceApplication`, `SpringApplication.run()` bootstrap order, `application.yml` loading order, springdoc setup, introducing CORS/Actuator | `docs/architecture/bootstrap.md` |
| Inter-domain calls, Adapter pattern implementation examples (`application/adapter/`, `infrastructure/`), ACL | `docs/architecture/cross-domain.md` |
| Summary (TL;DR) of the 13 core design principles, index of known gaps | `docs/architecture/design-principles.md` |
| `@Component`/`@Service`/`@Repository`/`@Configuration` stereotypes, constructor injection, `@Bean` methods, circular dependencies and `@Lazy` | `docs/architecture/module-pattern.md` |
| Rate limiting, Resilience4j `RateLimiter`, `Filter`-based implementation | `docs/architecture/rate-limiting.md` |
| Placement of shared code (`common/`, `config/`, `database/`, `outbox/`, `auth/`), domain-agnostic utilities | `docs/architecture/shared-modules.md` |

## Verifying the implementation

```bash
./harness.sh <projectRoot>
```

Each FAIL item's rule name and message include a link to the related document. Open that document to fix the issue.

## Code formatting/linting (Spotless)

The harness only checks architectural rules (file placement, layering); it does not check general code quality such as formatting or unnecessary imports. `examples/build.gradle` fills this gap by applying [Spotless](https://github.com/diffplug/spotless) with
`googleJavaFormat` (AOSP style, 4-space indent).

```bash
cd examples
./gradlew spotlessCheck   # check formatting violations only (no fixes). CI runs this task.
./gradlew spotlessApply   # auto-fix violations
```

`./gradlew build` (via the `check` task) also includes `spotlessCheck`, so the build fails if formatting is broken.
After adding new code, it's recommended to run `spotlessApply` once before committing.

## Scaffolding — new domain generator

This is a script that builds the "practical implementation template" defined by `docs/reference.md` into real code by combining the actual code of Card (2nd domain, domain/JPA separated structure) and
Account (Repository/Outbox pattern), then generalizes it into a reusable form by parameterizing only the domain name. In one run it generates a single `status` field (PENDING/ACTIVE/CANCELLED) +
a `create()`/`activate()`/`cancel(reason)` Aggregate + Command/Query "Service" (CQRS, plain `@Service`
classes — not CommandBus/Handler) + one domain event + an `OutboxEventHandler` implementation +
Repository + JPA Entity/Mapper + REST Controller/DTO (with `@Operation`/`@ApiResponse`/`@Schema`
already filled in, cross-checked against the generated Aggregate's own error codes — see
`docs/architecture/api-response.md`) + Flyway migration.

It's written as a Python script so it doesn't need to build the Java/Gradle toolchain (runs instantly without Java compilation).

```bash
# Default: generates under ../examples/src/main/java/com/example/accountservice/<domain>/
python3 scripts/create_domain.py Coupon

# To generate into a different project (e.g. a scratch copy), specify the project root with --out
python3 scripts/create_domain.py LoyaltyCategory --out /path/to/scratch-project
```

**There is no module-registration step** — unlike nestjs (`@Module({ providers: [...] })`) or Go, Spring
automatically collects classes annotated with `@Service`/`@Component`/`@Repository`/`@RestController` across the whole classpath (component scanning). The new domain's `OutboxEventHandler` implementation is
also automatically collected by the shared `OutboxConsumer` (`outbox/OutboxConsumer.java`) via constructor-injected `List<OutboxEventHandler>`, so there is no central wiring file to edit by hand (confirmed directly that `AccountServiceApplication.java` has no place listing domain
beans either).

Verification steps right after generation:

```bash
cd <projectRoot>
./gradlew spotlessApply   # align the generated template's formatting with googleJavaFormat
./gradlew build           # compile + confirm no regressions in existing tests (including Testcontainers e2e)
bash harness.sh <projectRoot>
```

Generating a real domain unrelated to Account/Card (including multi-word/irregular-plural domains such as Coupon, LoyaltyCategory) confirms 0 harness FAILs. Because the generator uses naive pluralization rules (+s/+es/y→ies), for domains with irregular plurals (e.g. person → people) you may need to manually adjust generated names such as `find<Domains>`/`<domains>`/REST paths — the script's output guides you through this. What's generated is a structural skeleton (an empty CRUD-style starting point); fill in the actual business rules, error messages, and fields yourself after generation.

## Example code (full Account domain)

The `examples/` directory contains a full example implementation of the Account domain (account opening/deposit-withdrawal/suspend/resume/close + SES notifications).
Use this as a template when working on a new domain (just change the domain name).

**Patterns implemented in `examples/`**: Aggregate ID with hyphens removed, JWT authentication (Spring Security), Flyway migrations, three-tier testing (Domain/Application/E2E), structured logging/correlation ID, Dockerfile (multi-stage), Secrets Manager integration, Domain/JPA layer separation (`AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper`), soft-delete wiring (`Account.delete()` + `AccountRepository.delete()` + `DeleteAccountService`), the 4-field error response structure, direct Repository use in the Query Service (`GetAccountService`), graceful shutdown/Actuator probes (`server.shutdown: graceful` + liveness/readiness), fail-fast `@ConfigurationProperties` validation of `jwt.secret` (`JwtProperties`), rate limiting (dual defense via Resilience4j `RateLimiter`, the `RateLimitFilter`, and the `@RateLimiter` annotation), the Dockerfile `HEALTHCHECK` directive, Repository method naming (unified to `findAccounts`/`saveAccount`, with single-record lookups done via `take:1` plus extracting the first result), and Outbox-based event publishing (the `outbox/` package) — all of these are reflected in the actual code following the patterns defined by their respective documents (`aggregate-id.md`, `authentication.md`, `persistence.md`, `testing.md`, `observability.md`, `container.md`, `secret-manager.md`, `layer-architecture.md`, `repository-pattern.md`, `error-handling.md`, `cqrs-pattern.md`, `graceful-shutdown.md`, `config.md`, `rate-limiting.md`, `domain-events.md`). When writing new code, follow the correct pattern defined by each document rather than the current pattern in `examples/`.
