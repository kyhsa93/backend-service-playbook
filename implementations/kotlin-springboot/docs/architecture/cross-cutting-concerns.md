# 횡단 관심사 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md) 참조.

## Spring MVC 요청 파이프라인

root의 5단계(전처리 → 인증 → 검증 → Handler → 응답 변환)를 Spring MVC 구성 요소로 매핑한다.

| root 단계 | Spring 구성 요소 | 배치 |
|---|---|---|
| 1. 전처리 (Correlation ID) | `Filter` (`OncePerRequestFilter`) | 서블릿 필터 체인 최상단 |
| 2. 인증 | Spring Security `Filter` | 필터 체인, 전처리 다음 |
| 3. 입력 검증 | `@Valid` + Bean Validation | `@RestController` 메서드 파라미터 |
| 4. Handler | `@RestController` 메서드 | interfaces/rest/ |
| 5. 응답 변환/로깅 | `HandlerInterceptor` 또는 필터 | Handler 이후 |

Servlet `Filter`는 DispatcherServlet **이전/이후** 전체를 감싸고, `HandlerInterceptor`는 DispatcherServlet이 라우팅을 마친 뒤 특정 Handler 주변만 감싼다. Correlation ID처럼 라우팅 실패(404) 시에도 필요한 관심사는 `Filter`로, 로깅처럼 Handler 정보(어떤 컨트롤러/메서드인지)가 필요한 관심사는 `HandlerInterceptor`로 구현하는 것이 Spring의 관례다.

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
@Order(Int.MIN_VALUE)   // 가장 먼저 실행 — 인증/검증보다 앞선다
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
            MDC.remove(MDC_KEY)   // 스레드 재사용 대비 — 반드시 정리
        }
    }
}
```

Node의 `AsyncLocalStorage`에 해당하는 것이 SLF4J의 **MDC**(Mapped Diagnostic Context, 내부적으로 `ThreadLocal`)다. Spring MVC는 요청 하나당 스레드 하나를 사용하므로(서블릿 스레드 모델), `ThreadLocal` 기반 MDC로 요청 전체에 걸쳐 Correlation ID를 함수 인자 없이 전파할 수 있다. `finally` 블록에서 반드시 `MDC.remove()`해야 한다 — 스레드 풀이 스레드를 재사용하므로 정리하지 않으면 다음 요청이 이전 요청의 Correlation ID를 이어받는다.

로그 패턴에 `%X{correlationId}`를 추가하면 이후 모든 로그에 자동 포함된다 (상세는 [observability.md](observability.md)).

```xml
<!-- logback-spring.xml -->
<pattern>%d{ISO8601} [%X{correlationId}] %-5level %logger{36} - %msg%n</pattern>
```

---

## 인증 — Spring Security Filter

인증은 별도의 `JwtAuthenticationFilter`(Correlation ID 다음, 컨트롤러 이전)에서 처리한다. 상세는 [authentication.md](authentication.md) 참조. **현재 `examples/`는 이 단계 자체가 없다** — `AccountController`가 `@RequestHeader("X-User-Id")`로 클라이언트가 보낸 값을 검증 없이 신뢰한다. 이는 root의 "Handler는 순수하게, 인증은 Guard/Filter 단계에서" 원칙을 위반하는 알려진 갭이다.

---

## 입력 검증 — `@Valid` + Bean Validation

```kotlin
// interfaces/rest/Schemas.kt — 실제 코드
data class CreateAccountRequest(
    val currency: String,
    @field:NotBlank
    @field:Email
    val email: String,
)
```

```kotlin
// interfaces/rest/AccountController.kt — 실제 코드
@PostMapping
fun createAccount(@Valid @RequestBody request: CreateAccountRequest): CreateAccountResult
```

Kotlin `data class` 프로퍼티에 Bean Validation 애노테이션을 붙일 때는 `@field:` 사용 지정자(use-site target)가 필요하다 — Kotlin은 생성자 파라미터의 애노테이션을 기본적으로 파라미터 자체에 붙이는데, Jakarta Validation은 **필드**에 붙은 애노테이션을 검사하기 때문이다. `@field:`를 빠뜨리면 검증이 조용히 동작하지 않는다(컴파일 에러 없음) — Kotlin/Bean Validation 조합에서 가장 흔한 실수다.

검증 실패(`MethodArgumentNotValidException`)는 Spring이 자동으로 400을 반환하지만, root가 요구하는 `{ statusCode, code: "VALIDATION_FAILED", message, error }` 형식으로 맞추려면 `@ExceptionHandler(MethodArgumentNotValidException::class)`를 글로벌 핸들러에 추가해야 한다 — 상세는 [error-handling.md](error-handling.md).

---

## 응답 로깅 — HandlerInterceptor

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
// common/WebConfig.kt — Interceptor 등록
@Configuration
class WebConfig(private val requestLoggingInterceptor: RequestLoggingInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestLoggingInterceptor)
    }
}
```

`afterCompletion`은 예외 발생 여부와 무관하게 항상 호출되므로, `@ExceptionHandler`가 에러를 응답으로 변환한 뒤에도 응답 시간/상태 코드를 정확히 로깅할 수 있다.

---

## Domain 레이어에서 사용 금지

`Account`, `Money`, `Transaction`(`domain/` 패키지)은 `Filter`/`Interceptor`/`Logger` 어떤 것도 import하지 않는다 — harness의 `domain-purity` 검사가 `domain/`에 `@Service`/`@Component` 등 Spring 애노테이션이 없는지 확인하지만, 로거 직접 사용 여부는 검사하지 않는다. 그럼에도 원칙은 동일하게 지켜야 한다.

```kotlin
// 금지 — Aggregate 내부에서 로깅
class Account protected constructor() {
    fun suspend() {
        logger.info("계좌 정지")   // ← 금지. Application 레이어(Command Service)에서 로깅
        ...
    }
}
```

---

## 원칙

- **역할별 단계 분리**: Correlation ID/로깅은 `Filter`/`Interceptor`, 인증은 Security `Filter`, 검증은 `@Valid`, 비즈니스 규칙은 Domain.
- **Correlation ID는 필터 체인 최상단(`@Order(Int.MIN_VALUE)`)**: 인증/검증보다 먼저 실행되어야 실패 응답에도 포함된다.
- **MDC는 `finally`에서 정리**: 스레드 풀 재사용 시 컨텍스트 오염 방지.
- **Domain 레이어는 어떤 횡단 관심사 구성 요소도 import하지 않는다.**

### 관련 문서

- [authentication.md](authentication.md) — 인증 Filter 상세
- [observability.md](observability.md) — MDC 기반 구조화 로깅
- [error-handling.md](error-handling.md) — 에러 변환 위치
