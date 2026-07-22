# Rate Limiting (Spring Boot)

> A document contrasted with NestJS. There is no corresponding root document — rate limiting itself is an infrastructure topic that requires choosing a specific implementation library, so it isn't among the root's 24 topics.

## Current implementation

`build.gradle` has the `resilience4j-spring-boot3` dependency, and both layers of defense below are reflected in `examples/`: `common/web/RateLimitFilter` (global, based on HTTP method, excluding `/actuator/**`) and `@RateLimiter(name = "createAccount")` on `AccountController.createAccount` (annotation-based, configured via `resilience4j.ratelimiter.instances.createAccount`). `RequestNotPermitted` is converted by `common/web/GlobalExceptionHandler` (`@RestControllerAdvice`) into the 4-field format from [error-handling.md](error-handling.md).

`RateLimitFilter` injects `RateLimiterRegistry` (a bean Spring Boot auto-configures from `resilience4j.ratelimiter.instances.*` in `application.yml`) via its constructor, and looks up a named instance (`http-write`/`http-read`) per write (POST/PUT/PATCH/DELETE) vs. read (GET/HEAD) method — the same approach as kotlin-springboot's `RateLimitingFilter`. Values can be adjusted at deploy time via `application.yml`/environment variables/command-line arguments, with no code changes needed.

---

## Library choice — Resilience4j `RateLimiter`

In the Java/Spring ecosystem, two options are commonly used for rate limiting.

| Library | Characteristics |
|---|---|
| **Resilience4j** | Integrates with Spring Cloud Circuit Breaker, supports both annotation (`@RateLimiter`) and programmatic use, part of the same ecosystem as circuit breakers/retries |
| **Bucket4j** | Dedicated to the token-bucket algorithm, specialized for multiple instances sharing a counter in a distributed environment (Redis backend) |

At a stage that assumes single-instance deployment, like this repository, **Resilience4j** has a lower adoption cost since it works in-memory without a separate external store. If the number of instances grows and requests need to be limited **globally** rather than counted independently per instance, switching to Bucket4j + Redis should be considered — this repository is not yet at that stage.

```groovy
// build.gradle — actual code
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

---

## Where it's applied — globally via a `Filter`

In the Spring MVC request pipeline laid out by [cross-cutting-concerns.md](cross-cutting-concerns.md) (`Filter` → `SecurityFilterChain` → `@Valid` → Handler → `HandlerInterceptor`), rate limiting must run **before authentication** — if even unauthenticated requests are allowed unlimited retries, the authentication logic itself becomes a DoS target.

```java
// common/web/RateLimitFilter.java — actual code
package com.example.accountservice.common.web;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // after CorrelationIdFilter, before SecurityFilterChain
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    public RateLimitFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {

        boolean isRead = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(isRead ? "http-read" : "http-write");

        if (limiter.acquirePermission()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"statusCode\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests.\",\"error\":\"Too Many Requests\"}"
            );
        }
    }
}
```

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

- **Why it's implemented as a `Filter`**: as [cross-cutting-concerns.md](cross-cutting-concerns.md) explains, a `Filter` runs before `DispatcherServlet` (Controller routing) — rate limiting doesn't need to know which Controller will match, and in fact blocking before routing saves server resources.
- **Error response format**: it follows the 4-field (`statusCode`/`code`/`message`/`error`) format defined by [error-handling.md](error-handling.md) exactly — rate limiting must keep the same schema as every other error path so clients can handle it consistently.
- **A single named instance keyed by method**: rather than a separate counter per IP, a single `RateLimiter` instance looked up by name from `RateLimiterRegistry` per write/read method is shared across all clients (the same as kotlin-springboot). Restarting an instance resets the counter, and scaling out to multiple instances counts independently per instance (10/100 req/min per instance, not globally). This repository still assumes a single instance, so this isn't a problem, but in a multi-instance environment, this needs to switch to a Redis-based distributed counter (Bucket4j + Redis, or limiting at the API Gateway/load-balancer level).

---

## Per-endpoint granularity — the annotation approach

If a particular Controller method needs a different limit than the global `Filter`, use Resilience4j's annotation approach. `RateLimitFilter` (global, method-based) and `@RateLimiter` (per-endpoint, AOP-based) can be used together — they operate at different layers.

```yaml
# application.yml — add if introducing this
resilience4j:
  ratelimiter:
    instances:
      createAccount:
        limit-for-period: 5
        limit-refresh-period: 1m
        timeout-duration: 0
```

```java
// account/interfaces/rest/AccountController.java — actual code
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createAccount")
public CreateAccountResult createAccount(...) { /* ... */ }
```

When `@RateLimiter` exceeds its limit, it throws a `RequestNotPermitted` exception — an `@ExceptionHandler` that converts this to the 4-field format defined by [error-handling.md](error-handling.md) needs to be added to `@RestControllerAdvice` (global, see [bootstrap.md](bootstrap.md)).

```java
// common/web/GlobalExceptionHandler.java — actual code
@ExceptionHandler(RequestNotPermitted.class)
public ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted e) {
    return ResponseEntity.status(429)
            .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Too many requests."));
}
```

---

## Adjusting operational values

`resilience4j.ratelimiter.instances.*` (`createAccount`/`http-write`/`http-read` all included) lives in `application.yml`, but thanks to Spring Boot's relaxed binding it can be overridden at deploy time **with no code change or recompilation** — flexibility effectively on par with go (`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST` environment variables) and nestjs (`THROTTLE_*` environment variables), and the same approach as kotlin-springboot's `RateLimitingFilter` (looking up the `http-write`/`http-read` named instance).

| Method | Example |
|---|---|
| Environment variable (relaxed binding) | `RESILIENCE4J_RATELIMITER_INSTANCES_HTTP_WRITE_LIMITFORPERIOD=20` |
| Per-profile yml | add `resilience4j.ratelimiter.instances.http-write.limit-for-period: 20` to `application-prod.yml`, then start with `SPRING_PROFILES_ACTIVE=prod` |
| Command-line argument | `java -jar app.jar --resilience4j.ratelimiter.instances.http-write.limit-for-period=20` |
| `SPRING_APPLICATION_JSON` | `SPRING_APPLICATION_JSON='{"resilience4j":{"ratelimiter":{"instances":{"http-write":{"limit-for-period":20}}}}}'` |

To change only a specific endpoint's value, like `createAccount`, simply swap the instance name for that name instead of `http-write`/`http-read` (e.g. `RESILIENCE4J_RATELIMITER_INSTANCES_CREATEACCOUNT_LIMITFORPERIOD=10`).

## Principles

- **Double defense — global + per-endpoint**: apply a global limit by method (write/read) via `Filter`, and add a stricter limit to sensitive endpoints (like account creation) via the `@RateLimiter` annotation.
- **The error response follows the same 4-field format as every other error path** (see [error-handling.md](error-handling.md)).
- **Exclude health check/Actuator endpoints** — either override `RateLimitFilter`'s `shouldNotFilter()` or exclude `/actuator/**` from the URL pattern when registering the `Filter`.
- **Recognize ahead of time when to move from a single-instance in-memory counter to a distributed counter across multiple instances** — if there's a scale-out plan, consider Bucket4j + Redis from the start.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where rate limiting sits in the `Filter`/`HandlerInterceptor` pipeline
- [error-handling.md](error-handling.md) — the 4-field format of a 429 response
- [bootstrap.md](bootstrap.md) — registering the `@RestControllerAdvice` global exception handler
- [observability.md](observability.md) — metrics/logging for rate-limit rejection events

## Harness verification

`harness/src/rules/RateLimitWired.java` (rule: `rate-limit-wired`) catches the regression where `RateLimitFilter` is defined but never actually applied as dead code: it checks that it's registered as a Spring bean via `@Component`, that it dynamically looks up a named instance from `RateLimiterRegistry` rather than hardcoding limit values directly on a field via `RateLimiterConfig.custom()`, and that it isn't explicitly disabled via `FilterRegistrationBean.setEnabled(false)`. It also observes and records the use of the `@RateLimiter` annotation in `interfaces/`, but since the global Filter alone is acceptable by principle, its absence is not treated as a failure.
