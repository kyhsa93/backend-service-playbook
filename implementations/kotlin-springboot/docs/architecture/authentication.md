# 인증 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root authentication.md](../../../../docs/architecture/authentication.md) 참조.

## 알려진 갭 — 현재 예제는 인증을 하지 않는다

`examples/.../account/interfaces/rest/AccountController.kt`의 모든 엔드포인트는 클라이언트가 보낸 헤더를 검증 없이 그대로 신뢰한다:

```kotlin
// 현재 코드 — 절대 프로덕션에 반영하면 안 되는 패턴
@PostMapping
fun createAccount(
    @RequestHeader("X-User-Id") requesterId: String,   // ← 클라이언트가 임의로 지정 가능
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult =
    createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
```

누구든 `X-User-Id: owner-2` 헤더만 붙이면 다른 사용자의 계좌를 조회·조작할 수 있다. 아래는 root 원칙에 맞는 올바른 JWT/Bearer + Spring Security 패턴이다. **`examples/`에는 아직 반영되어 있지 않다.**

아래 예시를 실제로 적용하려면 `build.gradle.kts`에 `spring-boot-starter-security`와 JWT 라이브러리(`io.jsonwebtoken:jjwt-api`/`jjwt-impl`/`jjwt-jackson` 등)를 추가해야 한다. 현재 `examples/build.gradle.kts`에는 두 의존성 모두 없다.

---

## 레이어 배치 원칙

인증은 Interface 레이어(Spring Security `Filter`)에서만 처리한다. Domain/Application 레이어는 토큰이나 인증 컨텍스트를 전혀 알지 못한다.

```
Interface: JwtAuthenticationFilter가 토큰 추출 → 검증 → SecurityContext에 Authentication 저장
Application: Command/Query에 requesterId(String)만 포함 — 토큰 개념 없음
Domain: 인증 개념 전혀 없음. 순수 비즈니스 로직
```

```kotlin
// 금지 — Application Service에서 토큰 직접 검증
@Service
class CreateAccountService(private val jwtDecoder: JwtDecoder) {
    fun create(token: String, command: CreateAccountCommand) {
        val claims = jwtDecoder.decode(token)   // ← Interface 레이어 역할을 침범
        ...
    }
}
```

```kotlin
// 올바른 방식 — Controller가 SecurityContext에서 인증된 사용자 ID만 꺼내 Command에 포함
@PostMapping
fun createAccount(
    authentication: Authentication,
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult {
    val requesterId = authentication.name   // JWT subject
    return createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
}
```

---

## JWT 발급 — AuthService

```kotlin
// auth/application/AuthService.kt
package com.example.accountservice.auth.application

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Service
class AuthService(@Value("\${jwt.secret}") secret: String) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun sign(userId: String): String =
        Jwts.builder()
            .subject(userId)                                     // payload에는 userId만 담는다
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(key)
            .compact()
}
```

**토큰 payload에는 최소한의 정보만 담는다.** `userId`(subject)만 넣고 `email`/`role`/`permissions`는 넣지 않는다 — JWT는 서명만 되고 암호화되지 않으므로 base64 디코딩으로 누구나 읽을 수 있고, 역할/권한은 발급 이후 변경될 수 있어 토큰에 캐시하면 즉시 반영되지 않는다. 필요한 사용자 정보는 요청 처리 시점에 DB에서 조회한다.

---

## JWT 검증 — Spring Security Filter

`OncePerRequestFilter`를 상속해 `Authorization: Bearer <token>` 헤더를 검증하고 `SecurityContextHolder`에 인증 정보를 채운다.

```kotlin
// auth/infrastructure/JwtAuthenticationFilter.kt
package com.example.accountservice.auth.infrastructure

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(@Value("\${jwt.secret}") secret: String) : OncePerRequestFilter() {

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ")
            runCatching {
                val userId = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).payload.subject
                val authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = authentication
            }   // 검증 실패 시 SecurityContext를 비워둔 채 다음 필터로 — SecurityConfig가 401/403 처리
        }
        filterChain.doFilter(request, response)
    }
}
```

Kotlin의 `runCatching { }`이 `try { } catch (e: Exception) { }`를 표현식으로 축약해준다 — 검증 실패를 "인증 안 됨" 상태로 조용히 넘기는 이 패턴에 잘 맞는다.

---

## SecurityConfig — 인증 필요/불필요 엔드포인트 구분

Guard/Filter는 클래스 단위가 아니라 **경로 패턴 단위**로 적용한다(Spring Security의 관용 방식). 메서드별 애노테이션 누락 위험을 피하려면 화이트리스트 경로를 최소한으로 유지한다.

```kotlin
// auth/infrastructure/SecurityConfig.kt
package com.example.accountservice.auth.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(private val jwtAuthenticationFilter: JwtAuthenticationFilter) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/auth/sign-in", permitAll)
                authorize("/health/**", permitAll)
                authorize(anyRequest, authenticated)   // 그 외 모든 도메인 API는 인증 필요
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
    }
}
```

`anyRequest, authenticated`를 기본값으로 두고 화이트리스트만 명시적으로 열어주는 것이 root의 "메서드 레벨 적용은 누락 위험이 있다" 경고를 Spring Security 관용구로 구현한 것이다 — 새 엔드포인트를 추가할 때 실수로 인증을 빼먹을 수 없다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Filter 체인에서 인증 위치
- [error-handling.md](error-handling.md) — 인증 실패 401 응답 형식
