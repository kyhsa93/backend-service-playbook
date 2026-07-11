# Rate Limiting — Kotlin Spring Boot

## 현재 상태 — 적용 완료

`examples/build.gradle.kts`에 `resilience4j-spring-boot3` 의존성이 추가되어 있고, `common/RateLimitingFilter.kt`가 실제로 모든 HTTP 요청에 속도 제한을 건다. `application.yml`에 `http-write`(10건/60초)와 `http-read`(100건/60초) 두 개의 `RateLimiter` 인스턴스가 정의되어 있고, 필터가 요청 메서드(`GET`/`HEAD`는 read, 그 외는 write)에 따라 둘 중 하나를 선택한다. 제한 초과 시 429 응답은 root의 4필드(`statusCode`/`code`/`message`/`error`) 에러 형식을 그대로 따른다. 아래는 실제 구현 내용과 그 설계 근거다.

## 선택지 — Resilience4j vs Bucket4j

| 라이브러리 | 알고리즘 | 특징 |
|---|---|---|
| **Resilience4j `RateLimiter`** | Fixed window (일정 주기마다 리필) | Spring Boot 공식 스타터(`resilience4j-spring-boot3`) 제공, Circuit Breaker/Retry와 같은 생태계, 애노테이션 기반 |
| **Bucket4j** | Token bucket | 버스트 허용에 유연, 분산 환경(Redis 백엔드) 지원이 성숙 |

이 저장소는 이미 [scheduling.md](scheduling.md), [secret-manager.md](secret-manager.md) 등에서 별도 인프라 도입을 최소화하는 방향을 취하고 있으므로, **단일 인스턴스 기준이면 Resilience4j로 충분**하다. 여러 인스턴스로 수평 확장하며 인스턴스 간 공유된 제한이 필요해지면 Bucket4j + Redis 조합을 검토한다.

## 설치 — 실제 코드

```kotlin
// build.gradle.kts — 실제 코드
dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}
```

## 애노테이션 기반 적용 — `@RateLimiter`

Resilience4j는 AOP 기반 애노테이션을 Application Service 메서드에 직접 붙이는 방식을 지원한다.

```yaml
# application.yml — 추가 필요 (현재 없음)
resilience4j:
  ratelimiter:
    instances:
      createAccount:
        limit-for-period: 5        # 주기당 허용 요청 수
        limit-refresh-period: 60s  # 리필 주기
        timeout-duration: 0s       # 즉시 거부 (대기하지 않음)
```

```kotlin
// application/command/CreateAccountService.kt — 적용 예시 (제안)
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {
    @RateLimiter(name = "createAccount", fallbackMethod = "createRateLimited")
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.save(account)
        outboxRelay.processPending()
        return CreateAccountResult(/* ... */)
    }

    // fallbackMethod는 원본과 동일한 파라미터 + 예외 타입을 마지막 인자로 받는다
    private fun createRateLimited(command: CreateAccountCommand, e: RequestNotPermitted): CreateAccountResult {
        throw TooManyRequestsException()
    }
}
```

`@RateLimiter`를 Application Service 메서드에 붙이는 것은 root의 계층 분리 원칙과 다소 긴장 관계다 — Application 레이어가 "얼마나 자주 호출되는가"라는 배포/인프라 관심사를 알게 되기 때문이다. 이 저장소의 관례([cross-cutting-concerns.md](cross-cutting-concerns.md)가 Correlation ID/인증/검증을 Filter·Interceptor로 분리하는 것과 동일한 논리)를 따르면, **HTTP 계층에서 걸러내는 편이 더 일관적**이다 — 아래 Filter 방식을 우선 권장한다.

## 적용 지점 — `Filter` 기반 적용 (cross-cutting-concerns.md와 일관), 실제 코드

[cross-cutting-concerns.md](cross-cutting-concerns.md)는 Correlation ID를 `OncePerRequestFilter`로 구현한다. Rate limiting도 같은 파이프라인 위치(서블릿 필터 체인)에 배치하는 것이 root의 "역할별 단계 분리" 원칙과 일관적이다 — 인증/검증보다 앞서 걸러내야 불필요한 다운스트림 처리를 막을 수 있다.

```kotlin
// common/RateLimitingFilter.kt — 실제 코드
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
@Order(Int.MIN_VALUE + 1) // CorrelationIdFilter 다음, Spring Security 필터 체인(인증)보다 먼저
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
                        "message" to "요청이 너무 많습니다.",
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

`OncePerRequestFilter`를 쓰는 것은 [cross-cutting-concerns.md](cross-cutting-concerns.md)의 `CorrelationIdFilter`와 동일한 관용구다 — 제한 초과 시 `@RestControllerAdvice`([error-handling.md](error-handling.md))까지 요청이 도달하기 전에 필터 체인에서 차단하고, 응답 형식은 root가 요구하는 4필드(`statusCode`/`code`/`message`/`error`) 구조를 `ObjectMapper`로 직렬화해 그대로 따른다. 헬스체크(`/actuator/health/**`, [graceful-shutdown.md](graceful-shutdown.md) 참조)는 필터 진입 시점에 경로로 걸러 제외한다.

## 엔드포인트별 세분화 — 실제 코드

쓰기 API(계좌 생성, 입출금)는 읽기 API(계좌 조회)보다 강하게 제한하는 것이 일반적이다. Resilience4j는 이름별로 다른 `RateLimiter` 인스턴스를 등록할 수 있고, `http-write`/`http-read` 두 인스턴스가 실제로 정의되어 있다.

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

`RateLimitingFilter`가 `request.method`로 `GET`/`HEAD`인지 판단해 `rateLimiterRegistry.rateLimiter("http-read")` / `rateLimiterRegistry.rateLimiter("http-write")`를 선택한다. E2E 테스트는 기본 write 한도(10건/60초)보다 훨씬 많은 요청을 짧은 시간에 호출하므로, `AccountControllerE2ETest`/`NotificationE2ETest`는 테스트 컨텍스트에서만 `resilience4j.ratelimiter.instances.http-write.limit-for-period`를 `@DynamicPropertySource`로 넉넉하게 올려 잡는다.

## 운영값 조정

`http-write`/`http-read` 두 인스턴스 모두 `RateLimitingFilter`가 `RateLimiterRegistry`를 통해 이름으로 조회한다 — `application.yml`에 값을 직접 박아넣는 대신 스프링이 관리하는 레지스트리를 거치므로, Spring Boot의 relaxed binding으로 **코드 변경 없이** 배포 시점에 override할 수 있다. go(`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`)·nestjs(`THROTTLE_*`)와 동급의 유연성이며, java-springboot의 동급 `RateLimitFilter`(값이 코드에 하드코딩되어 있어 이 방식이 통하지 않는다 — [java-springboot rate-limiting.md](../../../java-springboot/docs/architecture/rate-limiting.md) "운영값 조정" 참고)와 달리 이 구현체는 이미 완전히 유연하다.

| 방법 | 예시 |
|---|---|
| 환경 변수 (relaxed binding) | `RESILIENCE4J_RATELIMITER_INSTANCES_HTTP_WRITE_LIMITFORPERIOD=20` |
| 프로파일별 yml | `application-prod.yml`에 `resilience4j.ratelimiter.instances.http-write.limit-for-period: 20` 추가 후 `SPRING_PROFILES_ACTIVE=prod`로 기동 |
| 커맨드라인 인자 | `java -jar app.jar --resilience4j.ratelimiter.instances.http-write.limit-for-period=20` |
| `SPRING_APPLICATION_JSON` | `SPRING_APPLICATION_JSON='{"resilience4j":{"ratelimiter":{"instances":{"http-write":{"limit-for-period":20}}}}}'` |

테스트가 `@DynamicPropertySource`로 `resilience4j.ratelimiter.instances.http-write.limit-for-period`를 override하는 것(위 "엔드포인트별 세분화" 절 참고)도 같은 매커니즘을 쓴다 — 운영값 override와 테스트값 override가 동일한 주입 지점을 공유한다.

## 원칙

- **`examples/`에 `RateLimitingFilter`로 적용 완료** — `common/RateLimitingFilter.kt` + `resilience4j-spring-boot3` 의존성.
- **파이프라인 위치는 인증/검증보다 앞** — `@Order(Int.MIN_VALUE + 1)`로 `CorrelationIdFilter` 다음, Spring Security 필터 체인보다 먼저 배치해 불필요한 다운스트림 처리(DB 조회, 트랜잭션 시작)를 막는다.
- **Filter로 구현해 cross-cutting-concerns.md의 파이프라인 모델과 일관성 유지** — Application Service에 `@RateLimiter` 애노테이션을 흩뿌리는 것보다 계층 분리가 명확하다.
- **429 응답도 root의 4필드 에러 형식을 따른다** ([error-handling.md](error-handling.md)).
- **단일 인스턴스는 Resilience4j로 충분, 수평 확장 시 Bucket4j + Redis 검토**.

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Filter 체인 순서, `@Order` 배치
- [error-handling.md](error-handling.md) — 에러 응답 4필드 구조
- [conventions.md](../conventions.md) — REST API 컨벤션 전반
