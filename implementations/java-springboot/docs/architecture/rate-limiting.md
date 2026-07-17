# Rate Limiting (Spring Boot)

> NestJS 대비 문서. 루트에는 대응 문서가 없다 — rate limiting 자체가 특정 구현 라이브러리 선택을 요구하는 인프라 주제라 root 24개 주제에 포함되지 않는다.

## 현재 구현

`build.gradle`에 `resilience4j-spring-boot3` 의존성이 있고, 아래 두 겹 방어가 모두 `examples/`에 반영되어 있다: `common/web/RateLimitFilter`(메서드 기준 전역, `/actuator/**` 제외)와 `AccountController.createAccount`의 `@RateLimiter(name = "createAccount")`(애노테이션 기반, `resilience4j.ratelimiter.instances.createAccount` 설정). `RequestNotPermitted`는 `common/web/GlobalExceptionHandler`(`@RestControllerAdvice`)가 [error-handling.md](error-handling.md)의 4필드 형식으로 변환한다.

`RateLimitFilter`는 `RateLimiterRegistry`(Spring Boot가 `application.yml`의 `resilience4j.ratelimiter.instances.*`로부터 자동 구성하는 빈)를 생성자로 주입받아, 쓰기(POST/PUT/PATCH/DELETE)/읽기(GET/HEAD) 메서드별로 named instance(`http-write`/`http-read`)를 조회한다 — kotlin-springboot의 `RateLimitingFilter`와 동일한 방식이다. 값은 코드 변경 없이 `application.yml`/환경 변수/커맨드라인 인자로 배포 시점에 조정할 수 있다.

---

## 라이브러리 선택 — Resilience4j `RateLimiter`

Java/Spring 생태계에서 rate limiting에 흔히 쓰이는 선택지는 두 가지다.

| 라이브러리 | 특징 |
|---|---|
| **Resilience4j** | Spring Cloud Circuit Breaker와 통합, 애노테이션(`@RateLimiter`) 또는 프로그래밍 방식 모두 지원, 서킷 브레이커/재시도와 같은 생태계 |
| **Bucket4j** | 토큰 버킷 알고리즘 전용, 분산 환경(Redis 백엔드)에서 여러 인스턴스가 카운터를 공유하는 데 특화 |

이 저장소처럼 단일 인스턴스 배포를 가정하는 단계에서는 **Resilience4j**가 별도 외부 저장소 없이 인메모리로 동작해 도입 비용이 낮다. 인스턴스가 여러 대로 늘어나 인스턴스별 독립 카운팅이 아니라 **전역** 요청 수를 제한해야 하면 Bucket4j + Redis로 전환을 검토한다 — 이 저장소는 아직 그 단계가 아니다.

```groovy
// build.gradle — 실제 코드
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

---

## 적용 지점 — `Filter`로 전역 적용

[cross-cutting-concerns.md](cross-cutting-concerns.md)가 정리한 Spring MVC 요청 파이프라인(`Filter` → `SecurityFilterChain` → `@Valid` → Handler → `HandlerInterceptor`)에서, rate limiting은 **인증보다 먼저** 실행되어야 한다 — 인증되지 않은 요청조차 무한정 재시도를 허용하면 인증 로직 자체가 DoS 표적이 된다.

```java
// common/web/RateLimitFilter.java — 실제 코드
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
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // CorrelationIdFilter 다음, SecurityFilterChain보다 먼저
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
                    "{\"statusCode\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"요청이 너무 많습니다.\",\"error\":\"Too Many Requests\"}"
            );
        }
    }
}
```

```yaml
# application.yml — 실제 코드
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

- **`Filter`로 구현하는 이유**: [cross-cutting-concerns.md](cross-cutting-concerns.md)가 설명하듯 `Filter`는 `DispatcherServlet`(Controller 라우팅)보다 먼저 실행된다 — rate limiting은 어떤 Controller가 매칭될지 알 필요가 없고, 오히려 라우팅 이전에 차단해야 서버 리소스를 아낀다.
- **에러 응답 형식**: [error-handling.md](error-handling.md)가 정의하는 4필드(`statusCode`/`code`/`message`/`error`) 형식을 그대로 따른다 — rate limiting도 다른 에러 경로와 동일한 스키마를 유지해야 클라이언트가 일관되게 처리할 수 있다.
- **메서드 기준 named instance**: IP별로 독립 카운터를 두지 않고, 쓰기/읽기 메서드별로 `RateLimiterRegistry`에서 이름으로 조회한 단일 `RateLimiter` 인스턴스를 전체 클라이언트가 공유한다(kotlin-springboot와 동일). 인스턴스를 재시작하면 카운터가 초기화되고, 여러 인스턴스로 스케일 아웃하면 인스턴스별로 독립 집계된다(전역 10·100 req/min이 아니라 인스턴스당 10·100 req/min). 이 저장소는 아직 단일 인스턴스 전제이므로 문제가 되지 않지만, 다중 인스턴스 환경에서는 Redis 기반 분산 카운터(Bucket4j + Redis, 또는 API Gateway/로드밸런서 레벨 제한)로 전환해야 한다.

---

## 엔드포인트별 세분화 — 애노테이션 방식

전역 `Filter`가 아니라 특정 Controller 메서드에만 다른 제한을 걸고 싶다면 Resilience4j의 애노테이션 방식을 쓴다. `RateLimitFilter`(전역, 메서드 기준)와 `@RateLimiter`(엔드포인트별, AOP 기반)는 함께 쓸 수 있다 — 서로 다른 계층에서 동작한다.

```yaml
# application.yml — 도입 시 추가
resilience4j:
  ratelimiter:
    instances:
      createAccount:
        limit-for-period: 5
        limit-refresh-period: 1m
        timeout-duration: 0
```

```java
// account/interfaces/rest/AccountController.java — 실제 코드
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createAccount")
public CreateAccountResult createAccount(...) { /* ... */ }
```

`@RateLimiter`가 제한을 초과하면 `RequestNotPermitted` 예외를 던진다 — 이를 [error-handling.md](error-handling.md)가 정의하는 4필드 형식으로 변환하는 `@ExceptionHandler`를 `@RestControllerAdvice`(전역, [bootstrap.md](bootstrap.md) 참고)에 추가해야 한다.

```java
// common/web/GlobalExceptionHandler.java — 실제 코드
@ExceptionHandler(RequestNotPermitted.class)
public ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted e) {
    return ResponseEntity.status(429)
            .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "요청이 너무 많습니다."));
}
```

---

## 운영값 조정

`resilience4j.ratelimiter.instances.*`(`createAccount`/`http-write`/`http-read` 모두)는 `application.yml`에 있지만, Spring Boot의 relaxed binding 덕분에 **코드 변경·재컴파일 없이** 배포 시점에 override할 수 있다 — go(`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST` 환경 변수)·nestjs(`THROTTLE_*` 환경 변수)와 사실상 동급의 유연성이며, kotlin-springboot의 `RateLimitingFilter`(`http-write`/`http-read` named instance 조회)와도 동일한 방식이다.

| 방법 | 예시 |
|---|---|
| 환경 변수 (relaxed binding) | `RESILIENCE4J_RATELIMITER_INSTANCES_HTTP_WRITE_LIMITFORPERIOD=20` |
| 프로파일별 yml | `application-prod.yml`에 `resilience4j.ratelimiter.instances.http-write.limit-for-period: 20` 추가 후 `SPRING_PROFILES_ACTIVE=prod`로 기동 |
| 커맨드라인 인자 | `java -jar app.jar --resilience4j.ratelimiter.instances.http-write.limit-for-period=20` |
| `SPRING_APPLICATION_JSON` | `SPRING_APPLICATION_JSON='{"resilience4j":{"ratelimiter":{"instances":{"http-write":{"limit-for-period":20}}}}}'` |

`createAccount`처럼 특정 엔드포인트의 값만 바꾸고 싶으면 인스턴스 이름만 `http-write`/`http-read` 대신 해당 이름으로 바꾸면 된다(예: `RESILIENCE4J_RATELIMITER_INSTANCES_CREATEACCOUNT_LIMITFORPERIOD=10`).

## 원칙

- **전역 + 엔드포인트별 이중 방어**: `Filter`로 메서드(쓰기/읽기) 기준 전역 제한을 걸고, 쓰기 API(계좌 생성 등)처럼 민감한 엔드포인트는 `@RateLimiter` 애노테이션으로 더 엄격한 제한을 추가한다.
- **에러 응답은 다른 에러 경로와 동일한 4필드 형식**을 따른다([error-handling.md](error-handling.md)).
- **헬스체크/Actuator 엔드포인트는 제외**한다 — `RateLimitFilter`의 `shouldNotFilter()`를 오버라이드하거나 `Filter` 등록 시 URL 패턴에서 `/actuator/**`를 제외한다.
- **단일 인스턴스 인메모리 → 다중 인스턴스 분산 카운터로의 전환 시점을 미리 인지**한다 — 스케일 아웃 계획이 있다면 처음부터 Bucket4j + Redis를 고려한다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `Filter`/`HandlerInterceptor` 파이프라인상 rate limiting의 위치
- [error-handling.md](error-handling.md) — 429 응답의 4필드 형식
- [bootstrap.md](bootstrap.md) — `@RestControllerAdvice` 전역 예외 처리 등록
- [observability.md](observability.md) — rate limit 거부 이벤트의 메트릭/로깅
