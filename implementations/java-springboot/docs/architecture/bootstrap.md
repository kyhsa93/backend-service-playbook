# App Bootstrap (Spring Boot)

> A document contrasted with NestJS. There is no corresponding root document — Spring Boot's own bootstrap mechanism is a framework-specific topic and isn't among the root's 24 topics.

## Current actual code — `AccountServiceApplication.java`

```java
// AccountServiceApplication.java — actual code, in full
package com.example.accountservice;

import com.example.accountservice.config.AwsProperties;
import com.example.accountservice.config.SesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

Unlike NestJS's `main.ts` (a structure spanning several lines, where `NestFactory.create()` is followed by imperatively applying pipes/filters/CORS/Swagger one by one via `app.use*()`), Spring Boot's bootstrap ends with just the `@SpringBootApplication` + `@EnableConfigurationProperties` annotations and a single `SpringApplication.run()` call. This isn't because functionality is missing — it's because Spring Boot distributes the same concerns via annotation-based auto-configuration. Below, each of these differences is mapped item by item. `@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})` explicitly registers the beans subject to the fail-fast validation described in [config.md](config.md).

---

## `@SpringBootApplication` — a composite of 3 meta-annotations

```java
@SpringBootApplication
// This is actually a meta-annotation combining the following three:
// @Configuration           — makes this class itself a config class that can hold @Bean definitions
// @EnableAutoConfiguration — inspects classpath dependencies (spring-boot-starter-web, -data-jpa, etc.) and auto-registers the necessary Beans
// @ComponentScan           — scans the entire subtree of the package containing this class (com.example.accountservice) and registers @Component/@Service/@Repository/@Controller classes as beans
public class AccountServiceApplication { ... }
```

- **`@EnableAutoConfiguration`**: NestJS requires each module to be listed explicitly in `AppModule`, like `imports: [TypeOrmModule.forRoot(...), ConfigModule.forRoot(...)]`. In Spring Boot, if `spring-boot-starter-data-jpa` is present in `build.gradle`, the support beans for `DataSource`/`EntityManagerFactory`/`JpaRepository` are **auto-registered purely because they are present on the classpath**. In this repository, `AccountServiceApplication` sits in the top-level package (`com.example.accountservice`), so `@ComponentScan` automatically covers the entire `account/` subtree (including the `notification` Technical Service inside it) — there is no explicit `imports` list.
- **Location determines scan scope**: moving `AccountServiceApplication` into a different package would cause `@Service`/`@Repository` classes in sibling/non-descendant packages to be missed by the scan. Placing it in the top-level package, as currently done, is correct.

---

## `SpringApplication.run()` bootstrap order

The single line `SpringApplication.run(AccountServiceApplication.class, args)` internally executes the following sequence:

1. **Prepare the `Environment`** — load `application.yml`, determine the active profile (`spring.profiles.active`), and merge OS environment variables/system properties according to `PropertySource` priority
2. **Create the `ApplicationContext`** — parse `@Configuration` classes (`SesConfig`, etc.)
3. **`@ComponentScan`** — register `@Component`/`@Service`/`@Repository`/`@RestController` beans
4. **Instantiate beans + inject dependencies** — build the dependency graph in constructor-injection order (see [module-pattern.md](module-pattern.md))
5. **Bind `@ConfigurationProperties` + validate with `@Validated`** — on failure, `ApplicationContext` initialization aborts (fail-fast, see [config.md](config.md))
6. **Start the embedded Tomcat (servlet container)** — begin listening on `server.port` (default 8080)
7. **Publish `ApplicationReadyEvent`** — from this point on, traffic can be received

Of this sequence, steps 1 (config loading) and 5 (validation) correspond to NestJS's `ConfigModule.forRoot({ validate: validateConfig })` — except that NestJS assembles this explicitly inside a `bootstrap()` function, whereas in Spring Boot, simply declaring a `@ConfigurationProperties` class is enough for `SpringApplication.run()` to automatically slot it into this sequence.

### `application.yml` config-loading order (lowest priority → highest)

```
1. application.yml (default values)
2. application-{profile}.yml (overrides for the profile activated via spring.profiles.active)
3. OS environment variables (values referenced via placeholders like ${AWS_REGION})
4. Command-line arguments (e.g. --server.port=9090)
```

This repository's final structure has settled on a two-file configuration: `application.yml` + `application-prod.yml` (production-profile overrides, no defaults) — finer-grained `spring.config.import` splits such as `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml` are judged to be unnecessary complexity given the size of this repository's configuration surface, and are not introduced. The per-concern split and the profile strategy are covered in detail in [config.md](config.md)'s "Splitting config files by concern." Here, the only point being made is that the loading order itself is part of bootstrap step 1.

---

## Global exception-handling wiring — common exceptions are global, domain exceptions are Controller-local

Spring Boot has no global filter registered explicitly at bootstrap time, unlike NestJS's `app.useGlobalFilters(new HttpExceptionFilter())` — instead, any class annotated with `@RestControllerAdvice` is automatically discovered by `@ComponentScan` and applied to **every** `@RestController`. The key difference from NestJS is that registration lives in the class declaration itself, not inside `main()`.

`common/web/GlobalExceptionHandler` (`@RestControllerAdvice`) handles domain-agnostic common exceptions globally — both `RequestNotPermitted` (rate limit) and `MethodArgumentNotValidException` (validation failure) live in this class. Domain-specific exceptions like `AccountException`/`CardException`/`AuthException` are kept in their respective Controllers (`AccountController`/`CardController`/`AuthController`) — the reason is documented in the class comment on `GlobalExceptionHandler`: each domain's own Controller is best positioned to know the mapping between its domain exception types and error codes.

```java
// common/web/GlobalExceptionHandler.java — actual code
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted e) { /* ... */ }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) { /* ... */ }
}
```

A `@RestControllerAdvice` class doesn't need any registration code added to `main()` — simply placing it in a shared package like `common/` is enough for `@ComponentScan` to activate it automatically. See [shared-modules.md](shared-modules.md).

---

## OpenAPI/Swagger

`build.gradle` has the `springdoc-openapi-starter-webmvc-ui` dependency:

```groovy
// build.gradle — actual code
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3'
```

Unlike NestJS's `SwaggerModule.createDocument()`, springdoc does not require assembling a document object in `main()` — simply adding the dependency causes it to read `@RestController`/`@RequestMapping`/`@Valid` annotations via reflection and **automatically** expose `/v3/api-docs` and `/swagger-ui.html`. Title/version and the Bearer auth scheme are customized via a single `@Bean OpenAPI` in a `@Configuration` class:

```java
// config/OpenApiConfig.java — actual code (excerpt)
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Account Service API").version("0.1.0"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
```

This lives in `config/` (alongside `SecurityConfig`/`WebConfig`), not under `common/config/` — `common/config/` is reserved for infrastructure-level concerns that must run before the Spring context exists (`SecretsEnvironmentPostProcessor`, an `EnvironmentPostProcessor`), while `OpenApiConfig` is an ordinary `@Bean`-declaring `@Configuration` class like every other file already in `config/`.

Every `@RestController`'s operations are annotated with `@Operation` (`summary`/`description`) and `@ApiResponse` (one per status code the handler can actually return, cross-checked against its `@ExceptionHandler`'s error-mapping — not just the success response) — see the root [api-response.md](../../../../docs/architecture/api-response.md) "Machine-readable API documentation (OpenAPI)" section for the exact completeness bar, and `harness/src/rules/ApiDocumentation.java` (rule: `api-documentation`) for the mechanical check that enforces it.

---

## CORS configuration — not currently adopted; extend the existing `WebConfig`

`config/WebConfig.java` already exists — it's a `WebMvcConfigurer` implementation that registers `RequestLoggingInterceptor` (see [cross-cutting-concerns.md](cross-cutting-concerns.md)). When introducing CORS, **add `addCorsMappings(...)` to this existing `WebConfig` rather than creating a new class** — creating a separate class named/located as `common/config/WebConfig.java` would collide in name with the actual `config/WebConfig.java`.

```java
// config/WebConfig.java — if CORS is added to the actual code (proposal, only addCorsMappings is new)
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.isBlank() ? new String[]{} : allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true);
    }
}
```

Since Spring Security is used (see [authentication.md](authentication.md)), it's better to add CORS via `.cors(...)` on `SecurityConfig`'s `SecurityFilterChain` rather than `WebMvcConfigurer.addCorsMappings(...)` — configuring both `WebMvcConfigurer` and `SecurityFilterChain` redundantly can result in only one of them unexpectedly taking effect, depending on filter order. Since this repository uses Spring Security, it's recommended to place CORS configuration on the `SecurityConfig` side; the `WebConfig.addCorsMappings(...)` example above is only a reference for a minimal setup without Spring Security.

---

## Actuator / health checks — not currently adopted

`build.gradle` has no `spring-boot-starter-actuator`. If introduced, no bootstrap code changes are needed — adding the dependency and configuring `application.yml` alone automatically exposes `/actuator/health/liveness` and `/actuator/health/readiness`. Detailed configuration and graceful-shutdown integration are already covered in [container.md](container.md)'s "Health check endpoint" and in [graceful-shutdown.md](graceful-shutdown.md) — here, the only point being made is that this is part of the bootstrap stage (the auto-registered Actuator endpoints).

---

## Summary — NestJS `main.ts` vs. Spring Boot bootstrap mapping table

| NestJS `main.ts` | Spring Boot equivalent | Location |
|---|---|---|
| `NestFactory.create(AppModule)` | `SpringApplication.run(AccountServiceApplication.class, args)` | `AccountServiceApplication.java` |
| `app.enableShutdownHooks()` | Enabled by default (only needs `server.shutdown: graceful` added) | `application.yml` |
| `app.useGlobalPipes(new ValidationPipe())` | `@Valid` + `spring-boot-starter-validation` | Parameters of each Controller method |
| `app.useGlobalFilters(new HttpExceptionFilter())` | A `@RestControllerAdvice` class | `common/web/GlobalExceptionHandler.java` |
| `app.enableCors({...})` | `WebMvcConfigurer.addCorsMappings()` (not currently adopted) | `config/WebConfig.java` (add to the existing file — don't create a new class) |
| `SwaggerModule.setup('api', app, document)` | Auto-exposed simply by adding the `springdoc-openapi` dependency | `build.gradle` + `config/OpenApiConfig` |
| `app.listen(process.env.PORT ?? 3000)` | `server.port` (default 8080) | `application.yml` |

Core difference: NestJS lists its bootstrap concerns **imperatively** in `main.ts`, while Spring Boot distributes each concern via **declarative annotations + classpath auto-configuration** — leaving `main()` with only the single `SpringApplication.run()` line.

---

### Related documents

- [error-handling.md](error-handling.md) — details of `@RestControllerAdvice` global exception handling
- [config.md](config.md) — `application.yml` loading, profiles, `@ConfigurationProperties`
- [container.md](container.md) — Actuator health checks, container environment
- [graceful-shutdown.md](graceful-shutdown.md) — `server.shutdown: graceful`
- [authentication.md](authentication.md) — CORS placement change when Spring Security is introduced
