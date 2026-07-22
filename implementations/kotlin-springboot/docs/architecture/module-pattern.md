# The Spring DI Container — Kotlin Spring Boot

NestJS **explicitly** declares its dependency graph via `@Module({ providers, controllers, exports, imports })`. Spring has no such module-declaration file — **component scanning (classpath scanning) + stereotype annotations** implicitly perform the same role. This document covers the same topics NestJS's `module-pattern.md` covers (the DI container mechanism, avoiding circular dependencies, package organization) in terms of how Spring actually does it.

## Stereotype annotations — role per layer

| Annotation | Layer it's placed in | Role |
|---|---|---|
| `@Service` | `application/{command,query}/` | one class per use case |
| `@Repository` | `infrastructure/persistence/` | the Repository interface's implementation |
| `@Component` | `application/event/`, `infrastructure/` | other general beans (Adapter implementations, Listeners, etc) |
| `@Configuration` | `infrastructure/`, `config/` | classes holding `@Bean` factory methods |
| `@RestController` | `interfaces/rest/` | HTTP endpoints |

The harness's `service-annotation` (`@Service` only inside `application/`) and `repository-annotation` (`@Repository` only inside `infrastructure/`) checks actually enforce this placement — what NestJS's `providers` array registration does, is here replaced by a static check of "is the correct annotation attached in the correct package."

All four of these are meta-annotations of `@Component` (they compose `@Component` internally) — the sole criterion Spring uses to register something as a bean ultimately boils down to "does it have a `@Component`-family annotation, and is it inside a package targeted by component scanning." Splitting them into `@Service`/`@Repository` is for runtime-behavior differences (some exception translation, etc) and code readability — it doesn't change whether something is registered for DI at all.

## Constructor injection — a Kotlin primary constructor is itself the DI declaration

```kotlin
// application/command/CreateAccountService.kt — actual code
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult { /* ... */ }
}
```

What Java Spring would spread across three places — a `private final AccountRepository accountRepository;` field declaration, an assignment statement in the constructor body, and (if there are multiple constructors) `@Autowired` — ends in Kotlin with **a single line declaring the primary constructor parameter**. `private val` performs the field declaration and assignment at once, and if there's only one constructor, Spring 4.3+ automatically recognizes it as the DI constructor without `@Autowired`.

**Why `open` is needed**: a Kotlin class is `final` by default and can't be subclassed, but Spring AOP (the transaction proxy for `@Transactional`, etc) needs to create a CGLIB class-inheritance-based proxy. The `kotlin("plugin.spring")` compiler plugin automatically makes any class annotated with `@Component`/`@Service`/`@Repository`/`@Configuration` `open`, so there's no need to write the `open` keyword in the source yourself — this plugin is applied in `build.gradle.kts`.

## `@Bean` — factory methods on a `@Configuration` class

A bean that can't be created via constructor injection alone (a third-party SDK client, etc) is registered as a `@Bean` function on a `@Configuration` class.

```kotlin
// notification/infrastructure/SesConfig.kt — actual code
@Configuration
class SesConfig {

    @Bean
    fun sesClient(
        @Value("\${AWS_REGION:us-east-1}") region: String,
        @Value("\${AWS_ACCESS_KEY_ID:test}") accessKeyId: String,
        @Value("\${AWS_SECRET_ACCESS_KEY:test}") secretAccessKey: String,
        @Value("\${AWS_ENDPOINT_URL:}") endpointUrl: String,
    ): SesClient {
        val builder = SesClient.builder()
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
            )
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        return builder.build()
    }
}
```

`SesClient` is automatically injected as a constructor parameter of `NotificationServiceImpl` (`@Component`) — no matter where `SesConfig` lives or which class needs `SesClient`, Spring wires it up by type. Even if this is changed to the improvement [config.md](config.md) suggests (injecting a `SesProperties` data class instead of `@Value`), the `@Bean` method's role itself stays the same.

**Functional style**: a Kotlin `@Bean` method is a plain function declared with the `fun` keyword — unlike Java's method-overloading rules, the fact that a single function determines the bean type via its return type, and Spring reads this via reflection, is the same as Java. The Kotlin-specific difference is limited to syntax (it can be shortened to a single-expression function like `fun sesClient(): SesClient = ...`).

## Package = the Bounded Context boundary

```
com.example.accountservice/
  account/           ← the Account Bounded Context — includes all 4 layers
    domain/
    application/
    infrastructure/
    interfaces/
  notification/      ← a Technical Service (a top-level shared package, not a Bounded Context, see shared-modules.md)
    application/
    infrastructure/
  AccountServiceApplication.kt
```

NestJS requires explicitly declaring "this package is one unit" via `@Module`, but in Kotlin/Spring, **the package itself is already the boundary** — there's no separate `AccountModule` class or registration file. Once the component-scan root is specified (the package of `AccountServiceApplication.kt`, which has `@SpringBootApplication` — `com.example.accountservice`), every subpackage below it becomes a scan target.

This is also a tradeoff compared to NestJS — there's no way to enforce in code "what this BC exposes externally," the way `@Module`'s `exports` array does. There's no mechanism blocking one BC's Service from being imported directly in another BC's Application layer (tests/code review/harness static checks substitute for this instead). The discipline of calling only through an Adapter ([cross-domain.md](cross-domain.md)) is what fills this gap as a team convention.

## Avoiding circular dependencies — Spring fails at startup

NestJS can work around a circular dependency (A → B → A) with `forwardRef()`. **Spring's constructor injection has no reasonable way to work around a circular dependency** — with constructor injection, a circular dependency causes **the application to fail to start** with a `BeanCurrentlyInCreationException`.

```
com.example.accountservice.account.application.command.CreateAccountService
  → requires: com.example.accountservice.user.application.service.UserService
    → requires: com.example.accountservice.account.application.query.GetAccountService  (circular!)

***************************
APPLICATION FAILED TO START
***************************

Description:
The dependencies of some of the beans in the application context form a cycle:
...
```

There's technically a way to work around this by injecting a proxy via the `@Lazy` annotation, but that's a stopgap that hides the root cause (the BC boundary is set up wrong, or the two services should really be one). **The recommended response is redesign, not a workaround.**

1. **Make the direction one-way via the Adapter pattern** — have only one side call the other as an Adapter, and switch the reverse direction to an Integration Event (asynchronous) ([cross-domain.md](cross-domain.md), [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)).
2. **Extract commonly needed logic to a third location** — if there's logic both BCs depend on, pull it out into a Technical Service or a shared util ([shared-modules.md](shared-modules.md)).
3. **Reconsider the BC boundary itself** — a circular dependency is usually a sign that two "BCs" are actually one cohesive domain.

**The fact that Spring forcibly fails at startup is itself an advantage** — NestJS's `forwardRef()` makes the cycle "work," deferring the problem to the code-review stage, while Spring surfaces it immediately at the pre-deployment stage (local run, CI).

## Controller composition

```kotlin
// interfaces/rest/AccountController.kt — actual code (partial)
@RestController
@RequestMapping("/accounts")
class AccountController(
    private val createAccountService: CreateAccountService,
    private val depositService: DepositService,
    // ...
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestHeader("X-User-Id") requesterId: String,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult =
        createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
}
```

`AccountController` constructor-injects 8 Command/Query Services directly — there's no procedure like NestJS's, registering the Controller in a module's `controllers` array (`@RestController` itself is already a stereotype that includes `@Component`, so component scanning is sufficient).

## Summary — what's different from NestJS

| Concern | NestJS | Kotlin/Spring Boot |
|---|---|---|
| Declaring the bean-registration unit | an explicit `@Module({ providers: [...] })` array | package + stereotype annotation (implicit) |
| DI token | an `abstract class` or a string token | the `interface` itself is the token |
| Controlling external exposure | explicit via the `exports` array | no language/framework-level enforcement (convention + harness checks) |
| Circular dependency | can be worked around with `forwardRef()` | fails with a startup exception — forces a redesign |
| Conditional beans | a ternary inside the `imports` array | `@Profile`, `@ConditionalOnProperty` |

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction and DI tokens
- [cross-domain.md](cross-domain.md) — registering an Adapter implementation
- [shared-modules.md](shared-modules.md) — package placement of shared code
- [directory-structure.md](directory-structure.md) — the full package tree
