# 횡단 관심사 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md) 참고.

## 요청 파이프라인 — Spring의 구성 요소 매핑

root의 Middleware/Guard/Pipe/Interceptor 4단계는 Spring MVC에서 아래처럼 대응된다:

| root 단계 | Spring 메커니즘 | 이 저장소의 현재 상태 |
|------|----------------|------|
| 1. 전처리 (Correlation ID 등) | `Filter`(Servlet 표준) | 없음 |
| 2. 인증 | Spring Security `SecurityFilterChain` | 없음 (`X-User-Id` 헤더 그대로 신뢰, [authentication.md](authentication.md) 참고) |
| 3. 입력 검증 | `@Valid` + Bean Validation | 있음 (`@Valid @RequestBody CreateAccountRequest`) |
| 4. Handler | `@RestController` 메서드 | `AccountController` |
| 5. 응답 변환/로깅 | `HandlerInterceptor` 또는 AOP | 없음 |

**`Filter` vs `HandlerInterceptor`의 역할 구분**: `Filter`는 Servlet 컨테이너 레벨에서 동작해 Spring MVC의 `DispatcherServlet`보다 먼저 실행된다 — 인증처럼 "Spring이 요청을 라우팅하기도 전에 차단해야 하는" 관심사에 적합하다. `HandlerInterceptor`는 `DispatcherServlet` 내부, 실제 Controller 메서드 호출 전후에 위치해 어떤 Controller/메서드가 매칭되었는지 알 수 있다 — 요청 로깅처럼 "어떤 핸들러가 처리했는지"가 필요한 관심사에 적합하다.

---

## Correlation ID 주입 — `Filter` + MDC

```java
// infrastructure/web/CorrelationIdFilter.java — 제안, 현재 없음
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = Optional.ofNullable(request.getHeader(HEADER))
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
        MDC.put("correlation_id", correlationId);      // 이후 모든 로그에 자동 포함, observability.md 참고
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlation_id");                // 스레드 재사용 대비 필수 — 안 지우면 다음 요청에 값이 남는다
        }
    }
}
```

- **`OncePerRequestFilter`**: Spring이 제공하는 베이스 클래스로, 같은 요청 안에서 필터가 중복 실행(포워딩 등)되는 것을 방지한다.
- **`@Order(Ordered.HIGHEST_PRECEDENCE)`**: 여러 `Filter`가 등록될 때 실행 순서를 보장한다 — Correlation ID는 로깅/인증보다 먼저 설정되어야 다른 필터의 로그에도 포함된다.
- **`MDC`(Mapped Diagnostic Context)**: SLF4J/Logback이 제공하는 스레드 로컬 저장소로, root의 `AsyncLocalStorage` 기반 Correlation ID 전파를 Java에서 대체한다. `try-finally`로 반드시 정리한다 — Tomcat의 스레드 풀이 재사용되므로 정리하지 않으면 다음 요청 처리 시 이전 요청의 correlation_id가 로그에 남는다.

---

## 인증 — `SecurityFilterChain` (Guard 단계)

인증 로직 자체는 [authentication.md](authentication.md)에서 상세히 다룬다. 여기서는 파이프라인상의 위치만 확인한다: Spring Security의 `SecurityFilterChain`은 `Filter` 체인의 일부로 동작하며, `DispatcherServlet`(Controller 라우팅)보다 먼저 요청을 가로챈다 — root가 강조하는 "Guard는 Handler 진입 전에" 원칙이 그대로 실현된다.

```java
// authorizeHttpRequests가 전역(클래스 레벨 아닌 필터 체인 레벨)에 적용되는 것이
// root의 "메서드별 적용은 누락 위험" 원칙과 일치 — authentication.md 참고
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/health/**").permitAll()
        .anyRequest().authenticated());
```

---

## 입력 검증 — `@Valid` (Pipe 단계, 이미 구현됨)

```java
// AccountController.createAccount — 실제 코드
@PostMapping
public CreateAccountResult createAccount(
        @RequestHeader("X-User-Id") String requesterId,
        @Valid @RequestBody CreateAccountRequest request   // ← 형식적 검증은 Handler 진입 전
) {
    return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
}
```

`@Valid`는 Bean Validation(`spring-boot-starter-validation`, 이미 `build.gradle`에 있음)을 실행해 `CreateAccountRequest`의 `@NotBlank`/`@Email` 등 애노테이션을 검증한다 — 실패 시 `MethodArgumentNotValidException`이 Controller 메서드 본문 실행 전에 던져진다. **형식적 검증(필수값, 타입, 형식)과 비즈니스 규칙 검증(계좌 상태 확인 등)을 혼동하지 않는다** — 후자는 `Account` 도메인 메서드 내부(`deposit()`의 상태 체크 등)에서만 수행한다.

---

## HTTP 요청 로깅 — `HandlerInterceptor` (응답 후처리 단계)

```java
// infrastructure/web/RequestLoggingInterceptor.java — 제안, 현재 없음
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long durationMs = System.currentTimeMillis() - (long) request.getAttribute("startTime");
        log.info("{} {} status={} duration_ms={}", request.getMethod(), request.getRequestURI(),
                response.getStatus(), durationMs);
    }
}
```

```java
// config/WebConfig.java — Interceptor 등록
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final RequestLoggingInterceptor requestLoggingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor);
    }
}
```

`HandlerInterceptor`는 어떤 Controller 메서드가 매칭되었는지(`handler` 파라미터)까지 알 수 있어, `Filter`보다 세밀한 후처리(예: 특정 엔드포인트만 상세 로깅)가 가능하다. 이 저장소는 현재 요청 단위 로깅이 전혀 없다 — 개별 Service/Listener 내부의 `log.info`/`log.error`(`NotificationServiceImpl`, `AccountNotificationListener`)만 존재한다.

---

## Domain 레이어에서 횡단 관심사 사용 금지

`Filter`, `HandlerInterceptor`, Spring Security, MDC 등은 모두 Interface 레이어에 속한다. Domain 레이어에서 사용하지 않는다.

```java
// 금지 — Domain 레이어에서 로거/Spring 사용
public class Account {
    private static final Logger log = LoggerFactory.getLogger(Account.class);   // ← 금지

    public Transaction deposit(long amount) {
        log.info("입금 처리");   // ← 금지, Application 레이어에서 로깅
        // ...
    }
}
```

`Account`의 실제 코드는 이 원칙을 지키고 있다 — 어떤 도메인 메서드도 로깅하지 않는다.

---

## 원칙

- **역할에 맞는 Spring 메커니즘을 사용**: 인증은 Security Filter, 로깅은 Interceptor, 검증은 `@Valid`. 혼용하지 않는다.
- **전처리는 최대한 앞 단계에**: Correlation ID 주입은 `@Order(HIGHEST_PRECEDENCE)` Filter로 가장 먼저 실행한다.
- **Controller는 순수하게**: Service 호출과 Command/Query 변환만 담당한다.
- **MDC는 반드시 정리**: `try-finally`로 스레드 풀 재사용 시 값 누수를 막는다.

---

### 관련 문서

- [authentication.md](authentication.md) — 인증 패턴 상세
- [observability.md](observability.md) — MDC 기반 로깅, Correlation ID
- [error-handling.md](error-handling.md) — 에러 변환 위치
