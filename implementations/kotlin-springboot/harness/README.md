# Harness — Kotlin Spring Boot project structure/annotation rule checker

A static analysis tool that applies the **mechanically verifiable items** among the guide rules in `docs/`(shared) + `docs/architecture/*.md`(Kotlin implementation) to an external Kotlin Spring Boot project. It follows the design principles in the root [`../../../docs/harness.md`](../../../docs/harness.md) — it only evaluates compliance with architectural rules and does not assume specific business-domain knowledge like the Account domain in `examples/`.

Originally this was a pure bash+grep script (the design value at the time being "no installation needed"), but like the other harnesses in this repo (nestjs=TypeScript, go=Go), it was rewritten as a pure Kotlin program since **writing it in the same language as what it inspects** was judged to be the more idiomatic choice — it doesn't use a heavyweight build tool like Gradle, only direct compilation via `kotlinc`.

## Structure

```
harness/
  harness.sh                     compile(if needed)+run wrapper
  src/
    Main.kt                      CLI entry point — defines the rule list + aggregates/prints results
    Rule.kt                      shared types (Kind, Finding, RuleResult, Rule)
    KtFiles.kt                   shared helpers (collectKtFiles, relTo, pathContains, segmentBefore)
    rules/
      FileNaming.kt               one implementation file per rule
      RepositoryAnnotation.kt
      ServiceAnnotation.kt
      DomainPurity.kt
      ControllerPlacement.kt
      SealedException.kt
      PackageStructure.kt
      SharedInfra.kt
      EventPlacement.kt
      NoEventPublisherInCommand.kt
      TransactionBoundary.kt
      OutboxNoSyncDrain.kt
      CqrsPattern.kt
      NotificationE2eTest.kt
      RepositoryNaming.kt
      DomainLayerIsolation.kt
      InterfaceNoInfrastructure.kt
      AggregateNoPublicSetters.kt
      NoCrossAggregateReference.kt
      NoDirectEnvAccessOutsideConfig.kt
      NoCrossBcRepositoryInApplication.kt
      NoLoggingInDomain.kt
      SchedulerInInfrastructureOnly.kt
      NoSilentCatch.kt
      DockerfileConventions.kt
      AggregateIdFormat.kt
      ErrorResponseSchema.kt
      SoftDeleteFilter.kt
      TypedErrorsOnly.kt
      RateLimitWired.kt
  test/
    RuleTest.kt                   self-contained fixture test runner (no external framework dependency)
    testdata/<rule>/good/         minimal fixture that must pass the rule
    testdata/<rule>/bad-*/        fixture that must fail by violating the rule
  build/                          compiled output (gitignored, not committed)
```

Each rule function has the signature `harness.Rule`(`(String) -> RuleResult`) and is executed/printed in the order registered in `Main.kt`'s `RULES` list.

## Usage

```bash
# From the repository root — only recompiles when src/ has changed, otherwise reuses the cached jar
bash implementations/kotlin-springboot/harness.sh <projectRoot>
```

If `kotlinc` is not on PATH, specify it via the environment variable, e.g. `KOTLINC=/path/to/kotlinc bash harness.sh <projectRoot>`.

## Rule list

| Name | File | Role |
|------|------|------|
| `file-naming` | `FileNaming.kt` | whether the file name is `PascalCase.kt` |
| `repository-annotation` | `RepositoryAnnotation.kt` | `@Repository` → `infrastructure/` |
| `service-annotation` | `ServiceAnnotation.kt` | `@Service` → `application/` |
| `domain-purity` | `DomainPurity.kt` | forbids Spring annotations (`@Service`/`@Component`/`@Repository`/`@Controller`/`@RestController`) in `domain/` |
| `controller-placement` | `ControllerPlacement.kt` | `@RestController` → `interfaces/` |
| `sealed-exception` | `SealedException.kt` | `sealed class *Exception\|*Error` → `domain/` |
| `package-structure` | `PackageStructure.kt` | `application/{command,query}`, `infrastructure/`, `interfaces/` exist as siblings of `domain/` |
| `shared-infra` | `SharedInfra.kt` | when `OutboxWriter` is referenced, checks that `OutboxWriter.kt`/`OutboxPoller.kt`/`OutboxConsumer.kt` exist under `outbox/`; when `*TaskQueue*` is referenced, checks placement under `task-queue/` |
| `event-placement` | `EventPlacement.kt` | `*EventHandler`/`*IntegrationEvent`/`@EventListener` → `application/event/`(or integration-event) |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.kt` | fails if a Command Service uses `ApplicationEventPublisher`/`@EventListener`/`publishEvent()` — must go through the Outbox |
| `transaction-boundary` | `TransactionBoundary.kt` | checks that a Command Service has no `@Transactional`, and that the `*RepositoryImpl` that persists the Outbox does |
| `outbox-no-sync-drain` | `OutboxNoSyncDrain.kt` | fails if a Command Service references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls `processPending`/`poll`/`drainOnce` — publishing/consuming from the Outbox → queue is the sole responsibility of an independently periodic-run Poller/Consumer (no synchronous drain allowed, domain-events.md) |
| `cqrs-pattern` | `CqrsPattern.kt` | fails if a file in `application/query/` depends on the write-model Repository(`*Repository`) — it must only use the read-only Query interface(e.g. `AccountQuery`) (cqrs-pattern.md). Mentions inside comments are excluded |
| `notification-e2e-test` | `NotificationE2eTest.kt` | checks that `NotificationE2ETest.kt` exists (other rules exclude `test/` from their scan, so they can't catch this regression test being deleted) |
| `repository-naming` | `RepositoryNaming.kt` | checks that `*Repository`/`*Query` interface methods under `domain/`, `application/query/` follow `find<Noun>s`/`save<Noun>`/`delete<Noun>` naming (repository-pattern.md). Blocklists `findBy...` and bare `findAll`/`count*`/`save`/`delete`/`update*` — implementations under `infrastructure/` and internal Spring Data JPA interfaces (derived query methods) are not targeted |
| `domain-layer-isolation` | `DomainLayerIsolation.kt` | fails if a file in `domain/` imports the `application/`·`infrastructure/`·`interfaces/` package (whether its own domain or a sibling domain) (layer-architecture.md). A structural, path-based check (`com.example.accountservice.<any domain>.(application\|infrastructure\|interfaces)`) that doesn't hardcode domain names |
| `interface-no-infrastructure` | `InterfaceNoInfrastructure.kt` | fails if `interfaces/`(e.g. REST controllers) directly imports `infrastructure/` — it must depend only on `application/`(layer-architecture.md) |
| `aggregate-no-public-setters` | `AggregateNoPublicSetters.kt` | fails if a `var` property is not `private set` in the `class X private constructor()` idiom (Aggregate/Entity) under `domain/`(tactical-ddd.md "every property is `private set`"). `data class` Value Objects have no `var` to begin with, so they are not targeted |
| `no-cross-aggregate-reference` | `NoCrossAggregateReference.kt` | fails if, in `payment/domain/`, `Payment` directly references `Refund` as a field, or `Refund` directly references `Payment` — only ID references (`paymentId: String`) are allowed (domain-service.md `RefundEligibilityService` example). A Domain Service receiving multiple Aggregates as function parameters, like `evaluate(payment: Payment, refund: Refund)`, is not targeted |
| `no-direct-env-access-outside-config` | `NoDirectEnvAccessOutsideConfig.kt` | fails on a direct call to `System.getenv(...)` in `domain/`, `application/` — only `config/`(`@ConfigurationProperties`) and `infrastructure/` may access environment variables (config.md) |
| `no-cross-bc-repository-in-application` | `NoCrossBcRepositoryInApplication.kt` | fails if a file in `application/` directly imports another domain's `domain/*Repository`, `*Query` (not its own domain) — cross-domain lookups must go through an Adapter (`application/adapter/` + `infrastructure/*AdapterImpl`) (cross-domain-communication.md) |
| `no-logging-in-domain` | `NoLoggingInDomain.kt` | fails on use of a logging API such as SLF4J/`kotlin-logging` in `domain/`(observability.md "no logging in the Domain layer") |
| `scheduler-in-infrastructure-only` | `SchedulerInInfrastructureOnly.kt` | fails if `@Scheduled`/`@EnableScheduling` is in `domain/` or `application/`(scheduling.md). `outbox/`(shared infrastructure) and the top-level bootstrap class are outside both paths, so they pass |
| `no-silent-catch` | `NoSilentCatch.kt` | fails on finding a completely empty `catch (e: Exception) { }` block in `application/`, `infrastructure/`(observability.md) — if there is any content at all, such as logging or a rethrow, it's not targeted (a narrow blocklist to avoid false positives) |
| `dockerfile-conventions` | `DockerfileConventions.kt` | parses `examples/Dockerfile` as text to check for a multi-stage build (2+ `FROM`), the presence of `HEALTHCHECK`, and exclusion patterns for build artifacts/`.git` in `.dockerignore` (container.md) |
| `aggregate-id-format` | `AggregateIdFormat.kt` | checks that the project's `GenerateId.kt`(ID generation utility) does not return `UUID.randomUUID().toString()` as-is without removing hyphens — prevents a hyphenated UUID from being used as an Aggregate ID instead of 32-digit hex (no hyphens) (aggregate-id.md) |
| `error-response-schema` | `ErrorResponseSchema.kt` | checks that a `*ErrorResponse` data class has exactly 4 fields: `statusCode`(number)/`code`(String)/`message`(String or array)/`error`(String) — field names map directly to JSON serialization names, so even the casing must match exactly (error-handling.md) |
| `soft-delete-filter` | `SoftDeleteFilter.kt` | for a JPA Entity with a `deletedAt` soft-delete column, checks that the find queries in the `*RepositoryImpl.kt` that fetches that Entity include `deletedAt IS NULL`(or a `findBy...DeletedAtIsNull` derived query, or the Entity's `@SQLRestriction`/`@Where` global filter) — catches a violation of the no-hard-delete principle (deleted rows still being returned) (persistence.md). An Entity that has no `deletedAt` column at all (a domain with no delete use case) is not targeted |
| `typed-errors-only` | `TypedErrorsOnly.kt` | fails when a generic exception with a free-form string, such as `throw RuntimeException("...")`/`throw IllegalStateException("...")`, is constructed in `domain/`, `application/` — only typed subclasses of the `sealed class *Exception` hierarchy may be thrown (root AGENTS.md "type errors as enums", error-handling.md). Technical exceptions outside domain/application, such as in outbox/(infrastructure in nature), are not targeted |
| `rate-limit-wired` | `RateLimitWired.kt` | checks that an `OncePerRequestFilter` class referencing the Resilience4j `RateLimiter` actually calls limiting logic like `acquirePermission()`, and is either self-registered as a bean via `@Component` etc. or explicitly registered via `FilterRegistrationBean`/`addFilterBefore` — if either is missing, it's dead code that's only defined but never actually applied to the request pipeline (rate-limiting.md) |
| `no-generic-response-keys` | `NoGenericResponseKeys.kt` | fails if a `List<...>` property of a list-response `data class`(in `interfaces/`, `application/query/`, `application/command/`) uses a generic key like `result`/`data`/`items` — it must use the domain object's plural form (e.g. `transactions`, `payments`) (api-response.md) |
| `query-handler-no-raw-aggregate` | `QueryHandlerNoRawAggregate.kt` | fails if the return type of a function in a `@Service` Query Service in `application/query/` or a `@RestController` Controller exposes the raw Domain Aggregate/Entity of its own BC(`class X private constructor()`) as-is — a dedicated Result/DTO must be returned (api-response.md). A `*Query` read-only port interface(no `@Service`) used only inside the Query Service is not targeted |
| `no-cross-bc-domain-import` | `NoCrossBcDomainImport.kt` | fails if a file in `<bc>/domain/` directly imports another BC's `domain/` package — checks that the principle that other Aggregates may only be referenced by ID (tactical-ddd.md) applies not just within the same BC(`no-cross-aggregate-reference`) but also between BCs. Separately from `domain-layer-isolation`(no referencing higher layers), this also blocks sibling BCs' `domain/` from directly referencing each other |
| `no-orm-autosync-in-prod-config` | `NoOrmAutosyncInProdConfig.kt` | in `src/main/resources/application*.yml`(base + per-profile overrides, the actual production config), if `spring.jpa.hibernate.ddl-auto` is specified, only `validate`/`none` are allowed — `update`/`create`/`create-drop` are forbidden (persistence.md). Schema changes must be made only through Flyway migrations. Test `@DynamicPropertySource` config(`create-drop`) is unaffected since only `src/main/resources/`, not `src/test/`, is scanned |

## Regression tests

```bash
cd implementations/kotlin-springboot/harness
export JAVA_HOME=/path/to/jdk PATH="$JAVA_HOME/bin:$PATH"   # if needed
kotlinc src test/RuleTest.kt -include-runtime -d build/test.jar
cd test && java -cp ../build/test.jar harness.test.RuleTestKt
```

Each rule is verified with at least a `test/testdata/<rule>/good/`(must pass) and `test/testdata/<rule>/bad-*/`(must fail) fixture. No external test framework (JUnit, etc.) is pulled in — it's written using only self-contained assert functions that throw `AssertionError` on failure (the same approach as the nestjs harness's `run-fixtures.ts`).

When adding a new rule or modifying an existing one:
1. Implement (or modify) the logic in `src/rules/<Rule>.kt` — with the signature `fun check<Rule>(rootPath: String): RuleResult`
2. Write `test/testdata/<rule>/good/`, `test/testdata/<rule>/bad-*/` fixtures
3. Add the case to the `TESTS` list in `test/RuleTest.kt`(also register it in `RULES` in `src/Main.kt` if it's a new rule)
4. Verify using the "Regression tests" command above
