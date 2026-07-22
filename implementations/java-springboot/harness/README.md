# Harness — checks Java Spring Boot project structure/annotation rules

A static-analysis tool that applies the **machine-verifiable** subset of the guideline rules in `docs/` (shared) + `docs/architecture/*.md` (Java implementation) to an external Java Spring Boot project. It follows the design principles in the root [`../../../docs/harness.md`](../../../docs/harness.md) — it evaluates only compliance with architectural rules, and doesn't assume knowledge of a specific business domain such as the Account domain in `examples/`.

It's written as a plain Java program, matching the idiom of this repository's other harnesses (nestjs=TypeScript, go=Go, kotlin-springboot=Kotlin), each written in the same language as the project it checks — it uses no heavy build tool like Gradle/Maven, only a direct `javac` compile.

## Structure

```
harness/
  harness.sh                     Compile (if needed) + execute wrapper
  src/
    Main.java                    CLI entry point — defines the rule list + aggregates/prints results
    Kind.java                    PASS/FAIL/SKIP
    Finding.java                 A single rule check result
    RuleResult.java              One rule's section + list of findings
    Rule.java                    The rule function signature (String -> RuleResult)
    JavaFiles.java                Shared helpers (collectJavaFiles, relTo, pathContains, readText)
    rules/
      FileNaming.java             One implementation file per rule
      RepositoryAnnotation.java
      ServiceAnnotation.java
      DomainPurity.java
      ControllerPlacement.java
      PackageStructure.java
      SharedInfra.java
      EventPlacement.java
      NoEventPublisherInCommand.java
      TransactionBoundary.java
      OutboxDrainOrder.java
      CqrsQueryPurity.java
      RepositoryNaming.java
      DomainLayerIsolation.java
      InterfaceNoInfrastructure.java
      AggregateNoPublicSetters.java
      NoCrossAggregateReference.java
      NoDirectEnvAccessOutsideConfig.java
      NoCrossBcRepositoryInApplication.java
      NoLoggingInDomain.java
      SchedulerInInfrastructureOnly.java
      NoSilentCatch.java
      DockerfileConventions.java
      AggregateIdFormat.java
      ErrorResponseSchema.java
      SoftDeleteFilter.java
      TypedErrorsOnly.java
      RateLimitWired.java
      NoGenericResponseKeys.java
      QueryHandlerNoRawAggregate.java
      NoCrossBcDomainImport.java
      NoOrmAutoSyncInProdConfig.java
      ApiDocumentation.java
  test/
    RuleTest.java                 Self-contained fixture test runner (no external framework dependency)
    testdata/<rule>/good/         The minimal fixture that must pass this rule
    testdata/<rule>/bad-*/        The fixture that violates this rule and must fail
  build/                          Compiled output (gitignored, not committed)
```

Each rule is a static method with the `harness.Rule` (`Function<String, RuleResult>`) signature, and rules run and print in the order they're registered in `Main.java`'s `RULES` list.

## Usage

```bash
# From the repository root — recompiles only when src/ has changed, otherwise reuses the cached classes
bash implementations/java-springboot/harness.sh <projectRoot>
```

If `javac`/`java` isn't on PATH, specify them via environment variables, e.g. `JAVAC=/path/to/javac JAVA=/path/to/java bash harness.sh <projectRoot>`.

## List of rules

| Name | File | Role |
|------|------|------|
| `file-naming` | `FileNaming.java` | Whether the file name is `PascalCase.java` |
| `repository-annotation` | `RepositoryAnnotation.java` | `@Repository` → `infrastructure/` |
| `service-annotation` | `ServiceAnnotation.java` | `@Service` → `application/` |
| `domain-purity` | `DomainPurity.java` | Spring annotations (`@Service`/`@Component`/`@Repository`/`@Controller`/`@RestController`) are forbidden in `domain/` |
| `controller-placement` | `ControllerPlacement.java` | `@RestController` → `interfaces/` |
| `package-structure` | `PackageStructure.java` | `application/{command,query}`, `infrastructure/`, `interfaces/` exist as siblings of `domain/` |
| `shared-infra` | `SharedInfra.java` | If `OutboxWriter` is referenced, confirms `OutboxWriter.java`/`OutboxPoller.java`/`OutboxConsumer.java` exist under `outbox/`; if `*TaskQueue*` is referenced, confirms placement under `task-queue/` |
| `event-placement` | `EventPlacement.java` | `*EventHandler`/`@EventListener` → `application/event/`; `*IntegrationEvent` (allowing a `V1`/`V2`… version suffix) → `application/integrationevent/`; a `*EventHandler` inside `outbox/` is allowed as an exception for the Outbox dispatch contract |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.java` | Fails if a Command Service uses `ApplicationEventPublisher`/`@EventListener`/`publishEvent()` — it must go through the Outbox instead |
| `transaction-boundary` | `TransactionBoundary.java` | Confirms the Command Service has no `@Transactional`, and that the `*RepositoryImpl` that saves to the Outbox does have it |
| `outbox-drain-order` | `OutboxDrainOrder.java` | Fails if a Command Service (`application/command/`) directly references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls `processPending()`/`poll()`/`drainOnce()` — Outbox → queue publish/consume is the sole responsibility of the independently, periodically running Poller/Consumer (synchronous draining is forbidden, domain-events.md) |
| `cqrs-query-purity` | `CqrsQueryPurity.java` | Fails if a file under `application/query/` (excluding comments) references a write-side Repository type — a Query Service must depend only on a dedicated Query interface (e.g. `AccountQuery`) (cqrs-pattern.md). A rule ported from the nestjs harness's `cqrs-pattern` evaluator |
| `repository-naming` | `RepositoryNaming.java` | Fails if a `*Repository`/`*Query` interface method under `domain/`·`application/query/` hits the blocklist: a `findByXxx`-style derived query, a bare `findAll`, a method starting with `count`, a bare `save`/`delete` (no target noun), or a method starting with `update` (a separate update method is itself forbidden) — only the forms `find<Noun>s`/`save<Noun>`/`delete<Noun>` are allowed (repository-pattern.md). Implementations under `infrastructure/` and internal Spring Data JPA derived-query methods are not checked |
| `domain-layer-isolation` | `DomainLayerIsolation.java` | Fails if a `<domain>/domain/` file imports its own or a sibling domain's `application/`·`infrastructure/`·`interfaces/` — a structural check that looks only at import statements' package paths, so it hardcodes no specific framework name (layer-architecture.md) |
| `interface-no-infrastructure` | `InterfaceNoInfrastructure.java` | Fails if a file under `interfaces/` (REST Controller, etc.) directly imports `infrastructure/` — it must go through `application/` (the Command/Query Service) instead (layer-architecture.md) |
| `aggregate-no-public-setters` | `AggregateNoPublicSetters.java` | Fails if a `class`-declaring file under `domain/` has a JavaBean-style `public void setX(...)` or a Lombok `@Setter` — state changes must go through named domain methods only (tactical-ddd.md). Current Aggregates already avoid this pattern, so this is primarily a regression guard |
| `no-cross-aggregate-reference` | `NoCrossAggregateReference.java` | Fails if `payment/domain/Payment.java` directly references the `Refund` type, or `payment/domain/Refund.java` directly references the `Payment` type, as a field/parameter — only an ID-string reference is allowed (domain-service.md). A blocklist scoped to the one real case (the Payment BC) where two Aggregates coexist |
| `no-direct-env-access-outside-config` | `NoDirectEnvAccessOutsideConfig.java` | Fails on a direct `System.getenv(...)` call in `domain/`·`application/` — environment-variable access must be wrapped in `@ConfigurationProperties` and done only in `config/` (or `infrastructure/`) (config.md) |
| `no-cross-bc-repository-in-application` | `NoCrossBcRepositoryInApplication.java` | Fails if an `application/` file in one domain directly imports another domain's `domain/*Repository`·`application/query/*Query` interface — a cross-domain read must go through an Adapter (ACL) owned by the calling side (cross-domain-communication.md) |
| `no-logging-in-domain` | `NoLoggingInDomain.java` | Fails on use of `org.slf4j`/`@Slf4j`/`LoggerFactory` in `domain/` — logging is forbidden in the Domain layer (observability.md) |
| `scheduler-in-infrastructure-only` | `SchedulerInInfrastructureOnly.java` | Fails on use of `@Scheduled`/`@EnableScheduling` in `domain/`·`application/` — the Scheduler must be placed under infrastructure/ (scheduling.md). A legitimate use outside domain/application, like `outbox/OutboxPoller` (a shared infrastructure package) or `@EnableScheduling` on the bootstrap entry point, passes |
| `no-silent-catch` | `NoSilentCatch.java` | Fails if there's a completely empty `catch (...) {}` block in `application/`·`infrastructure/` — don't silently swallow an exception; log it and handle it, or rethrow it (observability.md) |
| `dockerfile-conventions` | `DockerfileConventions.java` | Confirms the `Dockerfile` is multi-stage (2+ `FROM` lines), has a `HEALTHCHECK` directive, and that `.dockerignore` exists and excludes `.git`/build output (container.md). The only rule that checks the two text files themselves rather than `.java` files |
| `aggregate-id-format` | `AggregateIdFormat.java` | Confirms `common/IdGenerator.java` strips the hyphens from `UUID.randomUUID().toString()` via `.replace("-", "")` — an Aggregate ID must be 32-character hex, not a 36-character hyphenated string (aggregate-id.md). ID generation is centralized into this one utility, so only a single file is checked |
| `error-response-schema` | `ErrorResponseSchema.java` | Dynamically finds the generic type of the `ResponseEntity<Xxx>` that `common/web/GlobalExceptionHandler.java`'s (`@RestControllerAdvice`) `@ExceptionHandler` methods return, and confirms that type has exactly these 4 fields: `statusCode` (number)/`code` (String)/`message` (String or array)/`error` (String) (error-handling.md). Parses the type that GlobalExceptionHandler actually returns, with no field name hardcoded — recognizes both a `record` and a plain `class` |
| `soft-delete-filter` | `SoftDeleteFilter.java` | Confirms a find method in `*RepositoryImpl.java` that reads a `*JpaEntity` with a `deletedAt` column has a `deletedAt IS NULL` (or equivalent) filter (persistence.md) — hard deletes are forbidden. If the Entity has a `@SQLRestriction`/`@Where` global filter, the RepositoryImpl check is skipped (either mechanism is accepted). An Entity with no `deletedAt` column (Card/Payment/Refund, etc., which have no delete use case yet) is naturally excluded from this check |
| `typed-errors-only` | `TypedErrorsOnly.java` | Fails if `domain/`·`application/` directly throws a generic exception with a string, like `throw new RuntimeException(...)`/`IllegalStateException(...)`/`IllegalArgumentException(...)`/`UnsupportedOperationException(...)`/`Exception(...)` — only a per-domain typed exception (`AccountException` + an `ErrorCode` enum) is allowed (error-handling.md, AGENTS.md "type errors as enums"). This pattern doesn't exist in the current code, so it's a pure regression guard |
| `rate-limit-wired` | `RateLimitWired.java` | Confirms `RateLimitFilter` is registered as a Spring bean via `@Component`, that it doesn't hardcode limit values via `RateLimiterConfig.custom()` but instead dynamically looks up a named instance from `RateLimiterRegistry`, and that it isn't explicitly disabled via `FilterRegistrationBean.setEnabled(false)` (rate-limiting.md). Also observes whether `interfaces/` uses the `@RateLimiter` annotation and records it as PASS, but not having one at all isn't a failure (optional) |
| `no-generic-response-keys` | `NoGenericResponseKeys.java` | Fails if a record following the `*Result`/`*Response`/`*WithCount` naming convention has a top-level `List<...>`-typed field named with a generic key like `result`/`data`/`items` — list-response field names must be the plural of the domain object's name (api-response.md). Only parses the top-level record matching the file name, and doesn't check a nested record (an inner class) — to avoid false-positiving on a sub-collection nested inside a single-record response (e.g. an order's line items) |
| `query-handler-no-raw-aggregate` | `QueryHandlerNoRawAggregate.java` | Fails if the return type of a `public` method on a Query Service under `application/query/` or a `interfaces/*Controller.java` directly exposes (or wraps in a generic like `List<...>`) a raw Aggregate/Entity class from `domain/` — it must always return a dedicated Result/DTO instead (api-response.md). The Aggregate/Entity names are not hardcoded; they're dynamically collected from `public class` declarations under `<bc>/domain/` (excluding `*Exception`/`*Service`). Only scans methods with an explicit `public` keyword (a Query interface's idiomatic modifier-omitted methods are out of scan scope — matching broadly without that requirement carries a high risk of false-positiving on a constructor call like `new XxxException(...)`, so a narrow blocklist approach was chosen) |
| `no-cross-bc-domain-import` | `NoCrossBcDomainImport.java` | Fails if `<bc>/domain/*.java` imports another BC's `<otherBc>/domain/*` type — the "another Aggregate may only be referenced by ID" principle (tactical-ddd.md) applies across BC boundaries too. A rule that closes a gap missed by both `no-cross-aggregate-reference` (which only looks at Payment/Refund within the same BC) and `domain-layer-isolation` (where domain/ only checks its own upper layers) |
| `no-orm-autosync-in-prod-config` | `NoOrmAutoSyncInProdConfig.java` | Fails if the `spring.jpa.hibernate.ddl-auto` value in either `src/main/resources/application.yml` (default) or `application-prod.yml` (prod profile) is `update`/`create`/`create-drop` — schema changes must be managed only via Flyway/Liquibase migrations (persistence.md). The default file is checked too because, if `SPRING_PROFILES_ACTIVE` is missing, its value applies in production as-is. If the `ddl-auto` key is absent, that means there's no automatic sync, so it's a PASS |
| `api-documentation` | `ApiDocumentation.java` | Fails if a `@RestController` operation's springdoc `@Operation` is missing a non-blank `summary`/`description`, or if no non-2xx `@ApiResponse` is documented for it — only documenting the success response is a fail (docs/architecture/api-response.md "Machine-readable API documentation (OpenAPI)"). A controller-wide `@ApiResponse` declared at the class level (e.g. a shared 401 for a missing/invalid bearer token) counts toward every operation in that class, not just the one it's textually closest to |

## Regression tests

```bash
cd implementations/java-springboot/harness
export JAVA_HOME=/path/to/jdk PATH="$JAVA_HOME/bin:$PATH"   # if needed
javac -d build/classes $(find src -name "*.java")
javac -cp build/classes -d build/test-classes test/RuleTest.java
cd test && java -cp ../build/classes:../build/test-classes RuleTest
```

Each rule is verified against at minimum a `test/testdata/<rule>/good/` fixture (must pass) and a `test/testdata/<rule>/bad-*/` fixture (must fail). No external test framework (JUnit, etc.) is pulled in — it's written with nothing but its own assert methods that throw `AssertionError` on failure (the same idea as the nestjs harness's `run-fixtures.ts`).

When adding a new rule or modifying an existing one:
1. Implement (or modify) the logic in `src/rules/<Rule>.java` — the `public static RuleResult check(String rootPath)` signature
2. Write the `test/testdata/<rule>/good/` and `test/testdata/<rule>/bad-*/` fixtures
3. Add a case to `test/RuleTest.java`'s `TESTS` list (and register it in `src/Main.java`'s `RULES` too, for a new rule)
4. Verify with the "Regression tests" command above
