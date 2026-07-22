# App Bootstrap — Kotlin Spring Boot

Compared to NestJS's `main.ts` (`implementations/nestjs/docs/architecture/bootstrap.md`), the first difference that stands out is **length**. Spring Boot's bootstrap function is nearly empty — that's not laziness, it's because **declarative configuration (annotations + component scan) replaces imperative `main()` calls**.

## The actual entry point

```kotlin
// AccountServiceApplication.kt — the entire actual code
package com.example.accountservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
```

That's it. Unlike NestJS's `bootstrap()`, which calls `app.useGlobalPipes(...)`, `app.useGlobalFilters(...)`, `app.enableCors(...)`, `SwaggerModule.setup(...)` in sequence inside one function, Kotlin/Spring Boot **scatters the same concerns across separate `@Component`/`@Configuration` classes, and component scanning assembles them**. `main()` doesn't list "what to turn on" — `@SpringBootApplication` is already a meta-annotation combining `@ComponentScan` + `@EnableAutoConfiguration` + `@Configuration`, so it automatically finds and registers the configuration classes on the classpath.

`runApplication<AccountServiceApplication>(*args)` is an extension function that wraps Java's `SpringApplication.run(AccountServiceApplication.class, args)` using Kotlin's [reified type parameter](https://kotlinlang.org/docs/inline-functions.html#reified-type-parameters) — not having to explicitly pass a `.class` token is the Kotlin-specific difference, but the bootstrap sequence that actually runs is identical to plain Java Spring Boot.

## Bootstrap sequence

```
1. runApplication<AccountServiceApplication>(*args)
2. Create the ApplicationContext, build the Environment
3. Load application.yml (+ application-{profile}.yml) → overridden by env vars/system properties
4. @ComponentScan — scans the entire com.example.accountservice subpackage tree
   → registers @Component/@Service/@Repository/@Configuration beans
5. Run @Bean factory methods (e.g. SesConfig.sesClient())
6. Start the embedded Tomcat, bind the port
7. Publish ApplicationReadyEvent — traffic can be received from this point on
```

## `application.yml` config loading order

The current `examples/src/main/resources/application.yml` looks like this.

```yaml
# application.yml — actual code
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

Spring Boot's configuration sources merge from lowest to highest priority as follows (later ones override earlier ones):

```
application.yml (defaults)
  → application-{spring.profiles.active}.yml (per-profile overrides)
    → environment variables (DATABASE_HOST, etc.)
      → command-line arguments (--server.port=8081)
```

How to bind per-concern configuration with `@ConfigurationProperties` + `data class`, and fail-fast validation, are covered in detail in [config.md](config.md) — at bootstrap time, all you need to remember is the order: "YAML → env var overrides → bound into the properties class activated via `@EnableConfigurationProperties`".

## Global exception handling — `@RestControllerAdvice`, not `main()`

NestJS registers this explicitly inside the bootstrap function, like `app.useGlobalFilters(new HttpExceptionFilter())`. In Spring Boot, **a class annotated with `@RestControllerAdvice` is automatically registered by component scanning**, so there's no `main()`-equivalent code for it.

```kotlin
// common/GlobalExceptionHandler.kt — excerpt of the actual code, see error-handling.md
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> = /* ... */
}
```

`AccountServiceApplication.kt` doesn't even need to know this class exists — being in the package and annotated with `@RestControllerAdvice` is enough. `examples/` is likewise consolidated into this single global handler, and `AccountController` has no `@ExceptionHandler` methods of its own — see [error-handling.md](error-handling.md) for details.

## OpenAPI/Swagger — `springdoc-openapi`

`examples/build.gradle.kts` declares `org.springdoc:springdoc-openapi-starter-webmvc-ui`. Unlike NestJS's `SwaggerModule.setup('api', app, document)` (an imperative call inside `bootstrap()`), springdoc doesn't assemble the document from a bootstrap function — the equivalent of NestJS's `DocumentBuilder` call is `config/OpenApiConfig.kt`, a `@Configuration` class with an `@Bean fun openApi(): OpenAPI` factory method that sets the title/description/version and registers the `bearerAuth` security scheme:

```kotlin
// config/OpenApiConfig.kt — excerpt of the actual code
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Account Service API").description("...").version("0.1.0"))
            .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme()./* ... */))
}
```

```yaml
# application.yml — actual code
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

Being a `@Configuration`/`@Bean` pair is enough for component scanning to register it — `AccountServiceApplication.kt`'s `main()` doesn't reference `OpenApiConfig` at all, the same "classpath scanning + annotations do the assembly, not a bootstrap function" pattern as every other cross-cutting concern in this document. Every Controller method is annotated with `@Operation`(summary/description) + `@ApiResponse`/`@ApiResponses`(one per non-2xx status it can actually return), and every request/response DTO field carries `@Schema(description = ...)` — see [api-response.md](../../../docs/architecture/api-response.md)'s "Machine-readable API documentation (OpenAPI)" section for the exact completeness bar, and `harness/README.md`'s `openapi-operation-documented` rule for how it's mechanically enforced. `SecurityConfig` also `permitAll`s `/v3/api-docs/**`/`/swagger-ui.html`/`/swagger-ui/**` — the generated docs are not a protected resource.

## CORS — not currently configured

There is no CORS-related configuration in `application.yml`, and no CORS mapping in `WebConfig` either (the `@Configuration` class covered in [cross-cutting-concerns.md](cross-cutting-concerns.md) that registers the `HandlerInterceptor`). If it were added, it would be declared on a `WebMvcConfigurer` implementation, not in the bootstrap function.

```kotlin
// common/WebConfig.kt — would need to be added (no CORS config currently)
@Configuration
class WebConfig(private val requestLoggingInterceptor: RequestLoggingInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestLoggingInterceptor)
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(*(System.getenv("CORS_ORIGIN")?.split(",")?.toTypedArray() ?: arrayOf("*")))
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowCredentials(true)
    }
}
```

## Summary — what's different from NestJS

| Concern | NestJS (`main.ts`) | Kotlin/Spring Boot |
|---|---|---|
| Registration approach | imperative calls inside the `bootstrap()` function | annotations + component scan (declarative) |
| Global validation | `app.useGlobalPipes(new ValidationPipe(...))` | `@Valid` + Bean Validation ([cross-cutting-concerns.md](cross-cutting-concerns.md)) |
| Global error handling | `app.useGlobalFilters(...)` | `@RestControllerAdvice` (auto-scanned, [error-handling.md](error-handling.md)) |
| API docs | `SwaggerModule.setup(...)` (assembled in bootstrap) | `OpenApiConfig`'s `@Bean fun openApi()` + `@Operation`/`@Schema` annotations on Controllers/DTOs — auto-registered by component scanning |
| Graceful shutdown | `app.enableShutdownHooks()` (must be called explicitly) | `SpringApplication.run()` already registers a shutdown hook ([graceful-shutdown.md](graceful-shutdown.md)) |
| Entry point | the `bootstrap()` function itself | `main()` — effectively a single call to `runApplication` |

**The key point**: `main()` being short doesn't mean less work happens at the bootstrap stage — Spring Boot's idiom is to spread the same concerns (validation, error handling, documentation, shutdown handling) across individual classes and let the framework scan/assemble them. The first habit a Kotlin/Spring developer needs to build is: when adding a new concern, don't modify `main()` — put a new class with the correct stereotype annotation in the correct package.

### Related documents

- [error-handling.md](error-handling.md) — details of `@RestControllerAdvice` global handling
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `@Valid`, the Filter/Interceptor chain
- [graceful-shutdown.md](graceful-shutdown.md) — shutdown hooks, Actuator probes
- [config.md](config.md) — `application.yml` loading and `@ConfigurationProperties`
- [module-pattern.md](module-pattern.md) — what component scanning actually registers
