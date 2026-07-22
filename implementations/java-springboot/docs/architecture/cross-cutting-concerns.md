# Cross-Cutting Concerns (Spring Boot)

> For the framework-agnostic principles, see the root [cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md).

## The request pipeline — mapping to Spring's components

The root's 4 stages of Middleware/Guard/Pipe/Interceptor map to Spring MVC as follows:

| Root stage | Spring mechanism | Current state in this repository |
|------|----------------|------|
| 1. Pre-processing (Correlation ID, etc.) | `Filter` (Servlet standard) | Present — `CorrelationIdFilter` |
| 2. Authentication | Spring Security `SecurityFilterChain` | Present — `SecurityConfig` (JWT, see [authentication.md](authentication.md)) |
| 3. Input validation | `@Valid` + Bean Validation | Present (`@Valid @RequestBody CreateAccountRequest`) |
| 4. Handler | `@RestController` method | `AccountController` |
| 5. Response transformation/logging | `HandlerInterceptor` or AOP | Present — `RequestLoggingInterceptor` + `WebConfig` |

**The division of responsibility between `Filter` and `HandlerInterceptor`**: `Filter` operates at the Servlet-container level and runs before Spring MVC's `DispatcherServlet` — suitable for concerns like authentication that need to be "blocked before Spring even routes the request." `HandlerInterceptor` sits inside `DispatcherServlet`, immediately before/after the actual Controller method call, so it knows which Controller/method was matched — suitable for concerns like request logging that need to know "which handler processed this."

---

## Injecting the Correlation ID — `Filter` + MDC

```java
// common/web/CorrelationIdFilter.java — actual code
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = Optional.ofNullable(request.getHeader(HEADER))
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
        MDC.put("correlation_id", correlationId);      // automatically included in every subsequent log line, see observability.md
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlation_id");                // essential given thread-pool reuse — without this, the value leaks into the next request
        }
    }
}
```

- **`OncePerRequestFilter`**: a Spring-provided base class that prevents a filter from running more than once within the same request (e.g. during forwarding).
- **`@Order(Ordered.HIGHEST_PRECEDENCE)`**: guarantees execution order when multiple `Filter`s are registered — the Correlation ID must be set before logging/authentication so it's included in the other filters' logs too.
- **`MDC` (Mapped Diagnostic Context)**: a thread-local store provided by SLF4J/Logback, replacing the root's `AsyncLocalStorage`-based Correlation ID propagation in Java. It must always be cleaned up with `try-finally` — since Tomcat's thread pool is reused, failing to clean up leaves the previous request's correlation_id in the logs of the next request handled by the same thread.

---

## Authentication — `SecurityFilterChain` (the Guard stage)

The authentication logic itself is covered in detail in [authentication.md](authentication.md). Here, only its position in the pipeline is confirmed: Spring Security's `SecurityFilterChain` operates as part of the `Filter` chain and intercepts the request before `DispatcherServlet` (Controller routing) — this directly realizes the root's principle that "a Guard runs before the Handler is entered."

```java
// SecurityConfig.java — actual code. Applying authorizeHttpRequests filter-chain-wide
// (rather than at the class level) matches the root's "applying per-method risks omissions"
// principle — see authentication.md
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/health/**", "/auth/sign-in").permitAll()
        .anyRequest().authenticated());
```

---

## Input validation — `@Valid` (the Pipe stage)

```java
// AccountController.createAccount — actual code
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public CreateAccountResult createAccount(
        Authentication authentication,
        @Valid @RequestBody CreateAccountRequest request   // ← formal validation happens before entering the Handler
) {
    String requesterId = authentication.getName();
    return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
}
```

`@Valid` runs Bean Validation (`spring-boot-starter-validation`, already in `build.gradle`) to check annotations like `@NotBlank`/`@Email` on `CreateAccountRequest` — on failure, a `MethodArgumentNotValidException` is thrown before the Controller method body executes. **Formal validation (required fields, type, format) is never confused with business-rule validation (checking account status, etc.)** — the latter is performed only inside `Account` domain methods (e.g. the status check in `deposit()`).

---

## HTTP request logging — `HandlerInterceptor` (the response-post-processing stage)

```java
// common/web/RequestLoggingInterceptor.java — actual code
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
    private static final String START_TIME_ATTR = "startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long durationMs = System.currentTimeMillis() - (long) request.getAttribute(START_TIME_ATTR);
        log.info("{} {} status={} duration_ms={}", request.getMethod(), request.getRequestURI(),
                response.getStatus(), durationMs);
    }
}
```

```java
// config/WebConfig.java — actual code, registering the Interceptor
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor);
    }
}
```

`HandlerInterceptor` even knows which Controller method was matched (the `handler` parameter), enabling more granular post-processing than `Filter` (e.g. detailed logging only for specific endpoints). When `config/WebConfig.java` introduces CORS (see [bootstrap.md](bootstrap.md)), `addCorsMappings(...)` can simply be added to the same class — both Interceptor registration and CORS configuration are consolidated into a single `WebMvcConfigurer` implementation.

---

## Cross-cutting concerns are forbidden in the Domain layer

`Filter`, `HandlerInterceptor`, Spring Security, MDC, and the like all belong to the Interface layer. They are never used in the Domain layer.

```java
// Forbidden — using a logger/Spring in the Domain layer
public class Account {
    private static final Logger log = LoggerFactory.getLogger(Account.class);   // ← forbidden

    public Transaction deposit(long amount) {
        log.info("Processing deposit");   // ← forbidden, logging belongs in the Application layer
        // ...
    }
}
```

The actual `Account` code follows this principle — no domain method performs logging.

---

## Principles

- **Use the Spring mechanism appropriate to the role**: authentication via a Security Filter, logging via an Interceptor, validation via `@Valid`. Never mix these.
- **Push pre-processing as early as possible**: Correlation ID injection runs first, via an `@Order(HIGHEST_PRECEDENCE)` Filter.
- **Keep Controllers pure**: they only call a Service and convert to/from Command/Query objects.
- **Always clean up MDC**: use `try-finally` to prevent value leakage when the thread pool is reused.

---

### Related documents

- [authentication.md](authentication.md) — details of the authentication pattern
- [observability.md](observability.md) — MDC-based logging, Correlation ID
- [error-handling.md](error-handling.md) — where error conversion happens
