# Cross-Cutting Concerns — Kotlin Spring Boot

> For the framework-agnostic principles, see [root cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md).

## The Spring MVC request pipeline

Maps the root's 5 stages (pre-processing → authentication → validation → Handler → response conversion) onto Spring MVC components.

| Root stage | Spring component | Placement |
|---|---|---|
| 1. Pre-processing (Correlation ID) | `Filter` (`OncePerRequestFilter`) | top of the servlet filter chain |
| 2. Authentication | Spring Security `Filter` | filter chain, after pre-processing |
| 3. Input validation | `@Valid` + Bean Validation | `@RestController` method parameters |
| 4. Handler | `@RestController` method | interfaces/rest/ |
| 5. Response conversion/logging | `HandlerInterceptor` or a filter | after the Handler |

A servlet `Filter` wraps the entire request **before/after** the DispatcherServlet, while a `HandlerInterceptor` only wraps around a specific Handler after the DispatcherServlet has finished routing. Spring's convention is to implement concerns that are needed even on a routing failure (404), like Correlation ID, as a `Filter`, and concerns that need Handler info (which controller/method it is), like logging, as a `HandlerInterceptor`.

---

## Correlation ID — Filter + MDC

```kotlin
// common/CorrelationIdFilter.kt
package com.example.accountservice.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

private const val HEADER = "X-Correlation-Id"
private const val MDC_KEY = "correlationId"

@Component
@Order(Int.MIN_VALUE)   // runs first — ahead of authentication/validation
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER) ?: UUID.randomUUID().toString().replace("-", "")
        response.setHeader(HEADER, correlationId)
        MDC.put(MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)   // must clean up in case the thread is reused
        }
    }
}
```

SLF4J's **MDC** (Mapped Diagnostic Context, backed internally by a `ThreadLocal`) is the equivalent of Node's `AsyncLocalStorage`. Since Spring MVC uses one thread per request (the servlet thread model), a `ThreadLocal`-based MDC can propagate the Correlation ID across the entire request without passing it as a function argument. `MDC.remove()` must always be called in a `finally` block — because the thread pool reuses threads, failing to clean up means the next request inherits the previous request's Correlation ID.

Adding `%X{correlationId}` to the log pattern automatically includes it in every subsequent log line (details in [observability.md](observability.md)).

```xml
<!-- logback-spring.xml -->
<pattern>%d{ISO8601} [%X{correlationId}] %-5level %logger{36} - %msg%n</pattern>
```

---

## Authentication — a Spring Security Filter

Authentication is handled by a separate `JwtAuthenticationFilter` (after Correlation ID, before the controller) — it's wired into the filter chain via `SecurityConfig`'s `addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)`. `AccountController` only pulls the authenticated user ID (`authentication.name`) out of the `SecurityContext` that `JwtAuthenticationFilter` populated, via the `Authentication` parameter — it never trusts a header sent by the client. See [authentication.md](authentication.md) for details.

---

## Input validation — `@Valid` + Bean Validation

```kotlin
// interfaces/rest/Schemas.kt — actual code
data class CreateAccountRequest(
    val currency: String,
    @field:NotBlank
    @field:Email
    val email: String,
)
```

```kotlin
// interfaces/rest/AccountController.kt — actual code
@PostMapping
fun createAccount(@Valid @RequestBody request: CreateAccountRequest): CreateAccountResult
```

Attaching a Bean Validation annotation to a Kotlin `data class` property requires the `@field:` use-site target — Kotlin attaches a constructor parameter's annotation to the parameter itself by default, but Jakarta Validation inspects annotations attached to the **field**. Omitting `@field:` makes validation silently not work (no compile error) — this is the most common mistake in the Kotlin/Bean Validation combination.

Spring automatically returns 400 on a validation failure (`MethodArgumentNotValidException`), but to match the root's required `{ statusCode, code: "VALIDATION_FAILED", message, error }` format, you need to add `@ExceptionHandler(MethodArgumentNotValidException::class)` to the global handler — see [error-handling.md](error-handling.md) for details.

---

## Response logging — HandlerInterceptor

```kotlin
// common/RequestLoggingInterceptor.kt
package com.example.accountservice.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RequestLoggingInterceptor : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(RequestLoggingInterceptor::class.java)
    private val startTimeAttr = "startTime"

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        request.setAttribute(startTimeAttr, System.currentTimeMillis())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val durationMs = System.currentTimeMillis() - (request.getAttribute(startTimeAttr) as Long)
        logger.info("{} {} -> {} ({} ms)", request.method, request.requestURI, response.status, durationMs)
    }
}
```

```kotlin
// common/WebConfig.kt — registering the Interceptor
@Configuration
class WebConfig(private val requestLoggingInterceptor: RequestLoggingInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestLoggingInterceptor)
    }
}
```

`afterCompletion` is always called regardless of whether an exception occurred, so response time/status code can be logged accurately even after an `@ExceptionHandler` has converted the error into a response.

---

## Forbidden in the Domain layer

`Account`, `Money`, `Transaction` (the `domain/` package) never import any `Filter`/`Interceptor`/`Logger` — the harness's `domain-purity` check verifies that `domain/` has no Spring annotations like `@Service`/`@Component`, but it doesn't check for direct logger usage. Even so, the principle must be observed just as strictly.

```kotlin
// forbidden — logging inside the Aggregate
class Account protected constructor() {
    fun suspend() {
        logger.info("Account suspended")   // ← forbidden. Log in the Application layer (Command Service) instead
        ...
    }
}
```

---

## Principles

- **Separate stages by responsibility**: Correlation ID/logging via `Filter`/`Interceptor`, authentication via a Security `Filter`, validation via `@Valid`, business rules in Domain.
- **Correlation ID sits at the very top of the filter chain (`@Order(Int.MIN_VALUE)`)**: it must run before authentication/validation so it's included even in failure responses.
- **Clean up MDC in `finally`**: prevents context contamination on thread-pool reuse.
- **The Domain layer never imports any cross-cutting-concern component.**

### Related documents

- [authentication.md](authentication.md) — details of the authentication Filter
- [observability.md](observability.md) — MDC-based structured logging
- [error-handling.md](error-handling.md) — where error conversion happens
