# Rate Limiting — Kotlin Spring Boot

## Current implementation

The `resilience4j-spring-boot3` dependency has been added to `examples/build.gradle.kts`, and `common/RateLimitingFilter.kt` actually rate-limits every HTTP request. Two `RateLimiter` instances are defined in `application.yml` — `http-write` (10 requests/60s) and `http-read` (100 requests/60s) — and the filter picks one based on the request method (`GET`/`HEAD` are read, everything else is write). When the limit is exceeded, the 429 response follows the root's 4-field (`statusCode`/`code`/`message`/`error`) error format as-is. Below is the actual implementation and its design rationale.

## Options — Resilience4j vs. Bucket4j

| Library | Algorithm | Characteristics |
|---|---|---|
| **Resilience4j `RateLimiter`** | fixed window (refills on a fixed cycle) | provided by Spring Boot's official starter (`resilience4j-spring-boot3`), same ecosystem as Circuit Breaker/Retry, annotation-based |
| **Bucket4j** | token bucket | flexible with allowing bursts, mature support for distributed environments (Redis backend) |

This repository already takes the approach of minimizing extra infrastructure elsewhere too (see [scheduling.md](scheduling.md), [secret-manager.md](secret-manager.md)), so **Resilience4j is sufficient for a single-instance setup**. If horizontally scaling to multiple instances requires a limit shared across instances, consider the Bucket4j + Redis combination.

## Installation — actual code

```kotlin
// build.gradle.kts — actual code
dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}
```

## Annotation-based application — `@RateLimiter`

Resilience4j supports attaching an AOP-based annotation directly to an Application Service method.

```yaml
# application.yml — would need to be added (not currently present)
resilience4j:
  ratelimiter:
    instances:
      createAccount:
        limit-for-period: 5        # allowed requests per period
        limit-refresh-period: 60s  # refill period
        timeout-duration: 0s       # reject immediately (no waiting)
```

```kotlin
// application/command/CreateAccountService.kt — example application (proposal)
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    @RateLimiter(name = "createAccount", fallbackMethod = "createRateLimited")
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)
        return CreateAccountResult(/* ... */)
    }

    // the fallbackMethod takes the same parameters as the original + the exception type as the last argument
    private fun createRateLimited(command: CreateAccountCommand, e: RequestNotPermitted): CreateAccountResult {
        throw TooManyRequestsException()
    }
}
```

Attaching `@RateLimiter` to an Application Service method is somewhat in tension with the root's layer-separation principle — it makes the Application layer aware of "how often is this called," a deployment/infrastructure concern. Following this repository's convention (the same logic by which [cross-cutting-concerns.md](cross-cutting-concerns.md) splits Correlation ID/authentication/validation into a Filter/Interceptor), **filtering at the HTTP layer is more consistent** — the Filter approach below is recommended first.

## Where it's applied — `Filter`-based application (consistent with cross-cutting-concerns.md), actual code

[cross-cutting-concerns.md](cross-cutting-concerns.md) implements Correlation ID as an `OncePerRequestFilter`. Placing rate limiting at the same pipeline position (the servlet filter chain) is consistent with the root's "separate stages by responsibility" principle — filtering before authentication/validation avoids unnecessary downstream processing.

```kotlin
// common/RateLimitingFilter.kt — actual code
package com.example.accountservice.common

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Int.MIN_VALUE + 1) // after CorrelationIdFilter, before the Spring Security filter chain (authentication)
class RateLimitingFilter(
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.requestURI.startsWith("/actuator/health")) {
            filterChain.doFilter(request, response)
            return
        }

        val isRead = request.method == HttpMethod.GET.name() || request.method == HttpMethod.HEAD.name()
        val rateLimiter = rateLimiterRegistry.rateLimiter(if (isRead) "http-read" else "http-write")

        if (!rateLimiter.acquirePermission()) {
            response.status = 429
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(
                objectMapper.writeValueAsString(
                    mapOf(
                        "statusCode" to 429,
                        "code" to "TOO_MANY_REQUESTS",
                        "message" to "Too many requests.",
                        "error" to "Too Many Requests",
                    ),
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }
}
```

Using `OncePerRequestFilter` is the same idiom as [cross-cutting-concerns.md](cross-cutting-concerns.md)'s `CorrelationIdFilter` — when the limit is exceeded, it's blocked in the filter chain before the request ever reaches `@RestControllerAdvice` ([error-handling.md](error-handling.md)), and the response format follows the root-required 4-field (`statusCode`/`code`/`message`/`error`) structure as-is, serialized via `ObjectMapper`. Health checks (`/actuator/health/**`, see [graceful-shutdown.md](graceful-shutdown.md)) are excluded by path right at filter entry.

## Per-endpoint granularity — actual code

It's common for write APIs (creating an account, deposits/withdrawals) to be limited more strictly than read APIs (querying an account). Resilience4j lets you register a different `RateLimiter` instance per name, and the two instances `http-write`/`http-read` are actually defined.

```yaml
# application.yml — actual code
resilience4j:
  ratelimiter:
    instances:
      http-write:
        limit-for-period: 10
        limit-refresh-period: 60s
        timeout-duration: 0s
      http-read:
        limit-for-period: 100
        limit-refresh-period: 60s
        timeout-duration: 0s
```

`RateLimitingFilter` decides via `request.method` whether it's `GET`/`HEAD` and picks `rateLimiterRegistry.rateLimiter("http-read")` / `rateLimiterRegistry.rateLimiter("http-write")` accordingly. Since the E2E tests call far more requests in a short time than the default write limit (10/60s), `AccountControllerE2ETest`/`NotificationE2ETest` raise `resilience4j.ratelimiter.instances.http-write.limit-for-period` generously via `@DynamicPropertySource`, only within the test context.

## Adjusting operational values

Both the `http-write`/`http-read` instances are looked up by name via `RateLimiterRegistry` inside `RateLimitingFilter` — instead of hardcoding values directly in `application.yml`, they go through a Spring-managed registry, so Spring Boot's relaxed binding lets you override them at deploy time **with no code changes**. This is on par with the flexibility of go (`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`)·nestjs (`THROTTLE_*`), and unlike java-springboot's equivalent `RateLimitFilter` (whose values are hardcoded in code, so this approach doesn't work there — see the "Adjusting operational values" section of [java-springboot rate-limiting.md](../../../java-springboot/docs/architecture/rate-limiting.md)), this implementation is already fully flexible.

| Method | Example |
|---|---|
| Environment variable (relaxed binding) | `RESILIENCE4J_RATELIMITER_INSTANCES_HTTP_WRITE_LIMITFORPERIOD=20` |
| Per-profile yml | add `resilience4j.ratelimiter.instances.http-write.limit-for-period: 20` to `application-prod.yml`, then start with `SPRING_PROFILES_ACTIVE=prod` |
| Command-line argument | `java -jar app.jar --resilience4j.ratelimiter.instances.http-write.limit-for-period=20` |
| `SPRING_APPLICATION_JSON` | `SPRING_APPLICATION_JSON='{"resilience4j":{"ratelimiter":{"instances":{"http-write":{"limit-for-period":20}}}}}'` |

The tests overriding `resilience4j.ratelimiter.instances.http-write.limit-for-period` via `@DynamicPropertySource` (see "Per-endpoint granularity" above) use this same mechanism — the operational-value override and the test-value override share the same injection point.

## Principles

- **`examples/`'s `RateLimitingFilter`** — `common/RateLimitingFilter.kt` + the `resilience4j-spring-boot3` dependency.
- **Its pipeline position is before authentication/validation** — placed via `@Order(Int.MIN_VALUE + 1)` right after `CorrelationIdFilter` and before the Spring Security filter chain, blocking unnecessary downstream processing (DB queries, starting a transaction).
- **Implemented as a Filter to stay consistent with cross-cutting-concerns.md's pipeline model** — clearer layer separation than scattering `@RateLimiter` annotations across Application Services.
- **The 429 response also follows the root's 4-field error format** ([error-handling.md](error-handling.md)).
- **Resilience4j is sufficient for a single instance; consider Bucket4j + Redis when scaling horizontally**.

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the Filter chain order, `@Order` placement
- [error-handling.md](error-handling.md) — the 4-field error response structure
- [conventions.md](../conventions.md) — general REST API conventions
- harness `rate-limit-wired` rule (`../../harness/README.md`) — mechanically verifies that a Filter referencing `RateLimiter` actually calls the limiting logic and is registered as a Spring bean applied to the request pipeline (not dead code)
