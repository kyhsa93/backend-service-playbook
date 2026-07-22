# Spring DI Container Pattern

> A document contrasted with NestJS. This is the concept corresponding to NestJS's `@Module` (explicit `providers`/`controllers`/`imports`/`exports` declarations), but Spring's DI container **does not enforce module boundaries at the language/framework level** — this document covers that difference, and how this repository actually organizes packages to achieve the same effect.

## The fundamental difference from NestJS's `@Module`

NestJS requires `@Module({ providers, controllers, imports, exports })` to **explicitly declare which beans belong to which module and which are exposed to which module** — without that declaration, injection from another module simply fails.

Spring Boot has no such declaration. `@ComponentScan` (in effect performed automatically by `@SpringBootApplication`) registers every `@Component`/`@Service`/`@Repository`/`@Configuration`/`@RestController` within its scan scope into a **single `ApplicationContext`**, and any class in any package can be auto-injected as long as the type matches. In other words:

- **NestJS**: module boundaries are enforced by code (a Provider that isn't `exports`ed by a module not listed in `imports` simply cannot be injected at all).
- **Spring Boot**: module boundaries are merely **a convention expressed through package structure** — never enforced by the compiler or container. Even if this repository adds a second BC, that package's classes can freely inject an `account` package `@Component` directly — the only thing preventing it is code review and architectural discipline.

This difference has trade-offs both ways. In NestJS, accidentally referencing another domain's internals directly breaks the build (a module-boundary violation surfaces at build time). In Spring Boot, the same mistake compiles silently and even works fine at runtime, so a convention like the Adapter pattern in [cross-domain.md](cross-domain.md) **has no way to be enforced besides code review** — this repository's `harness.sh` also doesn't check this particular aspect (forbidding direct cross-domain references).

---

## Stereotype annotations — both register a bean and mark the layer

Spring distinguishes layers through specializations of `@Component`. Below is the actual mapping this repository uses.

| Annotation | What it registers | Actual usage in this repository |
|---|---|---|
| `@Service` | An Application-layer use-case coordination service | `CreateAccountService`, `GetAccountService`, etc. |
| `@Repository` | An Infrastructure-layer Repository implementation | `AccountRepositoryImpl` |
| `@Component` | Any other general bean (Outbox event handlers, Technical Service implementations) | `AccountCreatedEventHandler`, `OutboxPoller`, `OutboxConsumer`, `NotificationServiceImpl` |
| `@Configuration` | A configuration class holding `@Bean` factory methods | `SesConfig` |
| `@RestController` | An HTTP entry point | `AccountController` |

Functionally, all four are identical in that `@ComponentScan` registers them all as beans — `@Service`/`@Repository` exist separately from `@Component` because (1) they make the layer's intent immediately visible in the code, and (2) `@Repository` additionally applies AOP that translates JPA/JDBC exceptions into Spring's `DataAccessException` hierarchy. This repository uses stereotypes matching the actual layer — it never puts `@Service` on a Repository implementation, or vice versa.

---

## Constructor injection — `@Autowired` field injection is never used

There is not a single instance of field injection (`@Autowired private final X x;`) anywhere in this repository — every class uses Lombok's `@RequiredArgsConstructor` for `final`-field constructor injection.

```java
// account/application/event/AccountSuspendedEventHandler.java — actual code
@Component
@RequiredArgsConstructor
public class AccountSuspendedEventHandler implements OutboxEventHandler {
    private final NotificationService notificationService;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    // Lombok auto-generates the constructor — no @Autowired needed
    // (Spring 4.3+ allows omitting @Autowired on a class with exactly one constructor)
}
```

Why constructor injection is used:
- **Enforces `final` fields** — dependencies cannot be reassigned after construction. Field injection cannot declare a field `final`.
- **Immutable dependencies surface circular dependencies immediately at startup, rather than at compile time** — see below.
- **Simpler mock injection in tests** — `new AccountSuspendedEventHandler(mockNotificationService, mockOutboxWriter, mockObjectMapper)` can be constructed directly without a Spring container (see [testing.md](testing.md)).

---

## `@Bean` methods — registering third-party types as beans

A class Spring doesn't own directly (an AWS SDK client, etc.) cannot carry `@Component` — instead, a `@Bean` factory method is placed inside a `@Configuration` class.

```java
// account/infrastructure/notification/SesConfig.java — actual code
@Configuration
public class SesConfig {

    @Bean
    public SesClient sesClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl,
            @Value("${aws.access-key-id:test}") String accessKeyId,
            @Value("${aws.secret-access-key:test}") String secretAccessKey
    ) {
        SesClientBuilder builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
```

- The `@Bean` method's **return type** (`SesClient`) is matched against injection-point types elsewhere and auto-injected — if `NotificationServiceImpl`'s constructor takes a `SesClient`, the instance returned by this method is injected there.
- Receiving config values directly as `@Value` method parameters is this repository's current approach. Replacing this with the `@ConfigurationProperties` record (`AwsProperties`) proposed by [config.md](config.md) would simplify the `@Bean` method to take a single `AwsProperties` parameter — the structure of the `@Bean` method itself stays the same.
- `SesConfig` lives in `account/infrastructure/notification/` (belonging to that domain). If a `@Configuration` were shared across multiple domains (e.g. a shared `ObjectMapper` bean), it would correctly belong in the shared package covered by [shared-modules.md](shared-modules.md) — this repository doesn't yet have such a shared configuration.

---

## Package = Bounded Context boundary (convention)

The Spring Boot convention corresponding to NestJS's "1 BC = 1 Module" is "1 BC = 1 top-level package."

```
com.example.accountservice/
  account/           ← Account BC — includes all 4 layers: domain/application/infrastructure/interfaces
    application/service/NotificationService.java        ← domain-scoped Technical Service interface
    infrastructure/notification/NotificationServiceImpl.java  ← implementation (not a separate BC, see directory-structure.md)
  AccountServiceApplication.java
```

- **The domain, not the layer, is the criterion for top-level package separation** — packages are never organized by technical layer, like `com.example.accountservice.controllers`, `com.example.accountservice.services`.
- As domains grow in number (e.g. `payment/`), a new top-level package is added at the same level, containing the same 4 layers inside it.
- **Unlike NestJS, there is no compile-time mechanism enforcing this structure** — see the "fundamental difference" section above. `harness.sh`'s `package-structure` check only confirms directory existence and cannot catch direct cross-domain references themselves.

---

## Circular dependencies — NestJS `forwardRef()` vs. Spring `@Lazy`

In NestJS, if two modules `import` each other, a cycle occurs, requiring a workaround via `forwardRef(() => OtherModule)`. Spring's constructor injection handles this problem **differently**.

- **A constructor-injection cycle fails immediately at startup.** If `A` takes `B` as a constructor parameter and `B` takes `A` as a constructor parameter, a `BeanCurrentlyInCreationException` occurs during `ApplicationContext` initialization, and the application doesn't even start up — unlike NestJS, where leaving a cycle unresolved without `forwardRef()` surfaces as a runtime error later, Spring **forces the developer to notice it immediately.**
- Spring also provides a `@Lazy` escape hatch — attaching `@Lazy` to an injection point injects a proxy first instead of the actual bean, deferring the real bean's initialization until the first method call, working around the cycle.

```java
// An example of "working around" a cycle with @Lazy — not recommended
@Service
public class ServiceA {
    public ServiceA(@Lazy ServiceB serviceB) { this.serviceB = serviceB; }
}
```

**This repository does not use `@Lazy` for the purpose of working around circular dependencies.** As NestJS's `module-pattern.md` states explicitly, a circular dependency is, in most cases, **a design signal that the Bounded Context boundary was drawn incorrectly** — neither `@Lazy` (Spring) nor `forwardRef()` (NestJS) does anything but technically paper over this signal, without addressing the root cause (redrawing the BC boundary, straightening the direction via an Adapter as in [cross-domain.md](cross-domain.md), or switching to event-based communication). Since this repository has a single-BC structure, no circular dependency has arisen here — this principle applies once a second BC is added.

---

## Conditional bean registration — `@Profile`

The Spring mechanism corresponding to NestJS's conditional module loading (`...(process.env.NODE_ENV === 'prd' ? [] : [DevToolModule])`) is `@Profile`.

```java
@Configuration
@Profile("!prod")   // active only when the profile is not prod
public class DevToolConfig {
    @Bean
    public DevDataSeeder devDataSeeder() { return new DevDataSeeder(); }
}
```

Among the beans `@ComponentScan` finds, any whose `@Profile` condition doesn't match the active profile determined by `spring.profiles.active` (see [config.md](config.md)) are simply skipped for registration — unlike NestJS's array-spread conditional, the condition is fixed at the point where the `@Configuration` class is declared.

---

## Summary

| NestJS concept | Spring Boot equivalent | Level of enforcement |
|---|---|---|
| `@Module({ providers, controllers })` | `@ComponentScan` (automatic, no declaration needed) | The container auto-registers; the boundary is a convention |
| `imports: [OtherModule]` | None — auto-injectable as long as it's the same `ApplicationContext` | Not enforced (relies on code review) |
| `exports: [SomeService]` | None — `public` is effectively globally exposed | Not enforced |
| `{ provide: Abstract, useClass: Impl }` | The sole implementation is auto-bound to an interface-typed injection point | The container resolves it automatically |
| `forwardRef(() => B)` | `@Lazy` (not recommended — redesigning the BC boundary comes first) | A developer's choice |
| Conditional module loading | `@Profile("...")` | The container applies it automatically |

---

### Related documents

- [cross-domain.md](cross-domain.md) — implementing the Adapter pattern for inter-domain calls
- [layer-architecture.md](layer-architecture.md) — per-layer dependency direction and stereotypes
- [config.md](config.md) — `@ConfigurationProperties`, per-profile configuration
- [shared-modules.md](shared-modules.md) — placement of `@Configuration`/`@Component` shared across multiple domains
- [testing.md](testing.md) — the Mockito-based unit testing that constructor injection enables
