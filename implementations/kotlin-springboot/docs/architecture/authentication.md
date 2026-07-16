# 인증 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root authentication.md](../../../../docs/architecture/authentication.md) 참조.

## 적용 완료 — JWT/Bearer + Spring Security + 실제 비밀번호 검증

`examples/.../account/interfaces/rest/AccountController.kt`의 모든 엔드포인트는 `Authentication` 파라미터에서 인증된 사용자 ID를 꺼내 쓴다 — 클라이언트가 보낸 헤더를 신뢰하지 않는다:

```kotlin
// 실제 코드
@PostMapping
fun createAccount(
    authentication: Authentication,
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult =
    createAccountService.create(CreateAccountCommand(authentication.name, request.currency, request.email))
```

`authentication.name`은 `JwtAuthenticationFilter`가 검증한 JWT의 `subject`(userId)다 — 클라이언트가 임의로 지정할 수 없다. 아래는 root 원칙에 맞는 JWT/Bearer + Spring Security 패턴이며, `auth/` 패키지(`AuthService`, `JwtAuthenticationFilter`, `SecurityConfig`, `Credential` Aggregate)로 실제 구현되어 있다.

`build.gradle.kts`에는 이미 `spring-boot-starter-security`(BCrypt 포함)와 JWT 라이브러리(`io.jsonwebtoken:jjwt-api`/`jjwt-impl`/`jjwt-jackson`)가 포함되어 있다.

---

## 가입 → 로그인 흐름

과거 `POST /auth/sign-in`은 `userId`만 받으면 비밀번호 확인 없이 JWT를 발급했다 — 다른 사용자의 `userId`만 알면 그 사용자로 완전히 가장할 수 있는 CRITICAL 취약점이었다(#190, 5개 언어 공통 발견). 지금은 `Credential` Aggregate(`credentialId`, `userId`, `passwordHash`)를 도입해 실제 비밀번호를 저장·검증한다.

```
[가입]
클라이언트 → 서버: POST /auth/sign-up { userId, password }
             SignUpService: 아이디 중복 확인(CredentialQuery) → PasswordHasher로 해싱 → Credential 저장(CredentialRepository)
             서버 → 클라이언트: 201

[로그인]
클라이언트 → 서버: POST /auth/sign-in { userId, password }
             SignInService: CredentialQuery로 저장된 해시 조회 → PasswordHasher.verify()로 비밀번호 검증
             검증 성공 시 AuthService.sign()으로 Access Token 발급
             서버 → 클라이언트: { accessToken }
```

**아이디 미존재와 비밀번호 불일치는 동일한 예외(`InvalidCredentialsException` → 401 `INVALID_CREDENTIALS`)로 응답한다** — 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration).

---

## 디렉토리 구조

```
auth/
  application/
    AuthService.kt                     ← 토큰 발급/검증 (JWT, Technical Service)
    command/
      SignUpCommand.kt / SignUpService.kt   ← 아이디 중복 확인 → 해싱 → 저장
      SignInCommand.kt / SignInService.kt   ← 해시 조회 → 검증 → 토큰 발급
    query/
      CredentialQuery.kt               ← 읽기 전용 포트 (findByUserId) — SignUp/SignIn 모두 사용
    service/
      PasswordHasher.kt                ← interface (Technical Service)
  domain/
    Credential.kt                      ← Aggregate (credentialId, userId, passwordHash, createdAt)
    CredentialRepository.kt            ← 쓰기 전용 포트 (saveCredential)
    AuthErrorCode.kt / AuthException.kt   ← sealed class 예외 계층
  infrastructure/
    BCryptPasswordHasher.kt            ← PasswordHasher 구현체 (Spring Security BCryptPasswordEncoder, strength 12)
    CredentialRepositoryImpl.kt        ← CredentialRepository + CredentialQuery 동시 구현
    persistence/
      CredentialJpaEntity.kt / CredentialJpaRepository.kt / CredentialMapper.kt
    JwtAuthenticationFilter.kt         ← Bearer 토큰 추출 및 검증 Filter
    SecurityConfig.kt                  ← 화이트리스트 경로 (`/auth/sign-in`, `/auth/sign-up`)
  interfaces/
    rest/
      AuthController.kt                ← POST /auth/sign-up, POST /auth/sign-in
```

**쓰기(`CredentialRepository`)와 읽기(`CredentialQuery`)를 분리한 이유** — `SignInService`는 자격증명을 저장하지 않는다(순수 조회 후 검증). `CredentialQuery`(읽기 전용 포트)만 의존하게 하면 `SignInService`가 실수로도 `saveCredential`을 호출할 수 없다 — Account/Card BC가 외부 GET 엔드포인트를 위해 `<Domain>Query`를 분리하는 것과 같은 원칙을, Auth에서는 내부 조회(중복 확인·해시 조회)에도 적용한 것이다([repository-pattern.md](repository-pattern.md), [cqrs-pattern.md](cqrs-pattern.md) 참고). `CredentialRepositoryImpl`(infrastructure) 하나가 두 인터페이스를 모두 구현해 실제 저장소는 하나다.

비밀번호 해싱은 이메일 발송(`NotificationService`)·Secrets Manager(`SecretService`)와 동일한 Technical Service 패턴이다 — `application/service/`에 interface, `infrastructure/`에 구현체를 두어 Domain/Application이 BCrypt 같은 구체 라이브러리에 의존하지 않게 한다([domain-service.md](../../../../docs/architecture/domain-service.md) 참고).

---

## Credential — 비밀번호 검증

```kotlin
// auth/domain/Credential.kt
class Credential private constructor() {
    var credentialId: String = ""; private set
    var userId: String = ""; private set
    var passwordHash: String = ""; private set   // 평문 비밀번호는 domain/application 어디에도 보관하지 않는다
    var createdAt: LocalDateTime = LocalDateTime.now(); private set

    companion object {
        fun create(userId: String, passwordHash: String): Credential = /* ... */
        fun reconstitute(credentialId: String, userId: String, passwordHash: String, createdAt: LocalDateTime): Credential = /* ... */
    }
}

// auth/application/service/PasswordHasher.kt — Technical Service interface
interface PasswordHasher {
    fun hash(plainPassword: String): String
    fun verify(plainPassword: String, passwordHash: String): Boolean
}

// auth/infrastructure/BCryptPasswordHasher.kt — 구현체
@Component
class BCryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(12)
    override fun hash(plainPassword: String): String = encoder.encode(plainPassword)
    override fun verify(plainPassword: String, passwordHash: String): Boolean = encoder.matches(plainPassword, passwordHash)
}
```

---

## SignUpService / SignInService

```kotlin
// auth/application/command/SignUpService.kt — 실제 코드
@Service
class SignUpService(
    private val credentialQuery: CredentialQuery,
    private val credentialRepository: CredentialRepository,
    private val passwordHasher: PasswordHasher,
) {
    fun signUp(command: SignUpCommand) {
        credentialQuery.findByUserId(command.userId)?.let { throw UserIdAlreadyExistsException() }
        val passwordHash = passwordHasher.hash(command.password)
        credentialRepository.saveCredential(Credential.create(command.userId, passwordHash))
    }
}

// auth/application/command/SignInService.kt — 실제 코드
@Service
class SignInService(
    private val credentialQuery: CredentialQuery,
    private val passwordHasher: PasswordHasher,
    private val authService: AuthService,
) {
    fun signIn(command: SignInCommand): String {
        // 아이디 미존재/비밀번호 불일치를 동일한 예외로 응답 — user enumeration 방지
        val credential = credentialQuery.findByUserId(command.userId) ?: throw InvalidCredentialsException()
        if (!passwordHasher.verify(command.password, credential.passwordHash)) throw InvalidCredentialsException()
        return authService.sign(credential.userId)
    }
}
```

`SignUpService`/`SignInService`는 둘 다 `POST` 엔드포인트의 유스케이스이므로 (cqrs-pattern.md의 Kotlin 구현 기준대로) `application/command/`에 둔다 — `SignInService`가 DB에 쓰지 않는다는 이유로 `application/query/`에 두지 않는다(그쪽은 `CredentialQuery` 포트 전용).

---

## JWT 발급 — AuthService (변경 없음)

```kotlin
// auth/application/AuthService.kt
@Service
class AuthService(jwtProperties: JwtProperties) {
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

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

## JWT 검증 — Spring Security Filter (변경 없음)

`OncePerRequestFilter`를 상속해 `Authorization: Bearer <token>` 헤더를 검증하고 `SecurityContextHolder`에 인증 정보를 채운다.

```kotlin
// auth/infrastructure/JwtAuthenticationFilter.kt
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
// auth/infrastructure/SecurityConfig.kt — 실제 코드
@Configuration
class SecurityConfig(private val jwtAuthenticationFilter: JwtAuthenticationFilter) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/auth/sign-in", permitAll)
                authorize("/auth/sign-up", permitAll)
                authorize("/actuator/health/**", permitAll)
                // STATELESS 세션 + OncePerRequestFilter 조합에서는 요청 처리 중 발생한 예외가
                // 컨테이너의 /error 재전달(forward)로 이어지는데, JwtAuthenticationFilter는
                // 기본적으로 error dispatch에서 재실행되지 않아 SecurityContext가 비어 401/400
                // 대신 403으로 응답이 뒤바뀐다 — /error도 permitAll로 열어 원래 상태 코드가 그대로
                // 전달되게 한다.
                authorize("/error", permitAll)
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

## Interface — AuthController

```kotlin
// auth/interfaces/rest/AuthController.kt — 실제 코드
@RestController
@RequestMapping("/auth")
class AuthController(
    private val signUpService: SignUpService,
    private val signInService: SignInService,
) {
    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUp(@Valid @RequestBody request: SignUpRequest) {
        signUpService.signUp(SignUpCommand(request.userId, request.password))
    }

    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody request: SignInRequest): SignInResponse =
        SignInResponse(signInService.signIn(SignInCommand(request.userId, request.password)))
}
```

`SignUpRequest.password`는 `@field:Size(min = 8)`로 최소 길이를 검증한다 — 위반 시 `MethodArgumentNotValidException` → `GlobalExceptionHandler`가 400 `VALIDATION_FAILED`로 변환한다([error-handling.md](error-handling.md) 참고). `AuthController`는 `SecurityConfig`의 화이트리스트(`/auth/sign-in`, `/auth/sign-up`)에 있으므로 `Authentication` 파라미터를 받지 않는다.

---

## 에러 처리 — INVALID_CREDENTIALS / USER_ID_ALREADY_EXISTS

```kotlin
// auth/domain/AuthException.kt
sealed class AuthException(message: String, val code: AuthErrorCode) : RuntimeException(message)

class InvalidCredentialsException :
    AuthException("아이디 또는 비밀번호가 올바르지 않습니다.", AuthErrorCode.INVALID_CREDENTIALS)
class UserIdAlreadyExistsException :
    AuthException("이미 사용 중인 아이디입니다.", AuthErrorCode.USER_ID_ALREADY_EXISTS)
```

`common/GlobalExceptionHandler.kt`가 `InvalidCredentialsException` → 401, 그 외 `AuthException`(현재는 `UserIdAlreadyExistsException`만 해당) → 400으로 변환한다 — Account/Card BC의 `<Domain>NotFoundException` → 404, 그 외 `<Domain>Exception` → 400 패턴과 동일하다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Filter 체인에서 인증 위치
- [error-handling.md](error-handling.md) — 인증 실패 401 응답 형식
- [repository-pattern.md](repository-pattern.md), [cqrs-pattern.md](cqrs-pattern.md) — Repository/Query 분리 원칙
