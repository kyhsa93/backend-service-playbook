# Authentication Pattern — Kotlin Spring Boot

> For the framework-agnostic principles, see [root authentication.md](../../../../docs/architecture/authentication.md).

## JWT/Bearer + Spring Security + password verification

Every endpoint in `examples/.../account/interfaces/rest/AccountController.kt` pulls the authenticated user ID out of the `Authentication` parameter — it never trusts a header sent by the client:

```kotlin
// actual code
@PostMapping
fun createAccount(
    authentication: Authentication,
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult =
    createAccountService.create(CreateAccountCommand(authentication.name, request.currency, request.email))
```

`authentication.name` is the JWT `subject` (userId) verified by `JwtAuthenticationFilter` — the client cannot set it arbitrarily. Below is the JWT/Bearer + Spring Security pattern aligned with the root principles, actually implemented in the `auth/` package (`AuthService`, `JwtAuthenticationFilter`, `SecurityConfig`, the `Credential` Aggregate).

`build.gradle.kts` already includes `spring-boot-starter-security` (with BCrypt) and a JWT library (`io.jsonwebtoken:jjwt-api`/`jjwt-impl`/`jjwt-jackson`).

---

## Sign-up → sign-in flow

`POST /auth/sign-in` issues a JWT only after verifying the actual password against the `Credential` Aggregate (`credentialId`, `userId`, `passwordHash`).

```
[Sign-up]
Client → Server: POST /auth/sign-up { userId, password }
             SignUpService: check for duplicate user ID (CredentialQuery) → hash with PasswordHasher → save Credential (CredentialRepository)
             Server → Client: 201

[Sign-in]
Client → Server: POST /auth/sign-in { userId, password }
             SignInService: look up the stored hash via CredentialQuery → verify password via PasswordHasher.verify()
             On successful verification, issue an access token via AuthService.sign()
             Server → Client: { accessToken }
```

**A non-existent user ID and a password mismatch both respond with the same exception (`InvalidCredentialsException` → 401 `INVALID_CREDENTIALS`)** — responding differently for each would let an attacker guess which user IDs exist (user enumeration).

---

## Directory structure

```
auth/
  application/
    AuthService.kt                     ← token issuance/verification (JWT, Technical Service)
    command/
      SignUpCommand.kt / SignUpService.kt   ← check for duplicate user ID → hash → save
      SignInCommand.kt / SignInService.kt   ← look up hash → verify → issue token
    query/
      CredentialQuery.kt               ← read-only port (findCredentials, the find<Noun>s rule from repository-pattern.md) — used by both SignUp/SignIn
    service/
      PasswordHasher.kt                ← interface (Technical Service)
  domain/
    Credential.kt                      ← Aggregate (credentialId, userId, passwordHash, createdAt)
    CredentialRepository.kt            ← write-only port (saveCredential)
    AuthErrorCode.kt / AuthException.kt   ← sealed class exception hierarchy
  infrastructure/
    BCryptPasswordHasher.kt            ← PasswordHasher implementation (Spring Security BCryptPasswordEncoder, strength 12)
    CredentialRepositoryImpl.kt        ← implements both CredentialRepository and CredentialQuery
    persistence/
      CredentialJpaEntity.kt / CredentialJpaRepository.kt / CredentialMapper.kt
    JwtAuthenticationFilter.kt         ← Filter that extracts and verifies the Bearer token
    SecurityConfig.kt                  ← whitelisted paths (`/auth/sign-in`, `/auth/sign-up`)
  interfaces/
    rest/
      AuthController.kt                ← POST /auth/sign-up, POST /auth/sign-in
```

**Why write (`CredentialRepository`) and read (`CredentialQuery`) are separated** — `SignInService` never saves credentials (it's a pure lookup followed by verification). Depending only on `CredentialQuery` (the read-only port) means `SignInService` can't accidentally call `saveCredential` even by mistake — this applies the same principle the Account/Card BC use when splitting off `<Domain>Query` for external GET endpoints, but here applied to internal lookups (duplicate check, hash lookup) within Auth as well (see [repository-pattern.md](repository-pattern.md), [cqrs-pattern.md](cqrs-pattern.md)). A single `CredentialRepositoryImpl` (infrastructure) implements both interfaces, so there is only one actual store.

Password hashing follows the same Technical Service pattern as sending email (`NotificationService`) and Secrets Manager (`SecretService`) — an interface lives in `application/service/` and the implementation in `infrastructure/`, so Domain/Application don't depend on a concrete library like BCrypt (see [domain-service.md](../../../../docs/architecture/domain-service.md)).

---

## Credential — password verification

```kotlin
// auth/domain/Credential.kt
class Credential private constructor() {
    var credentialId: String = ""; private set
    var userId: String = ""; private set
    var passwordHash: String = ""; private set   // the plaintext password is never stored anywhere in domain/application
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

// auth/infrastructure/BCryptPasswordHasher.kt — implementation
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
// auth/application/command/SignUpService.kt — actual code
@Service
class SignUpService(
    private val credentialQuery: CredentialQuery,
    private val credentialRepository: CredentialRepository,
    private val passwordHasher: PasswordHasher,
) {
    fun signUp(command: SignUpCommand) {
        credentialQuery
            .findCredentials(CredentialFindQuery(page = 0, take = 1, userId = command.userId))
            .first
            .firstOrNull()
            ?.let { throw UserIdAlreadyExistsException() }
        val passwordHash = passwordHasher.hash(command.password)
        credentialRepository.saveCredential(Credential.create(command.userId, passwordHash))
    }
}

// auth/application/command/SignInService.kt — actual code
@Service
class SignInService(
    private val credentialQuery: CredentialQuery,
    private val passwordHasher: PasswordHasher,
    private val authService: AuthService,
) {
    fun signIn(command: SignInCommand): String {
        // Responds with the same exception for a non-existent user ID and a password mismatch — prevents user enumeration
        val credential =
            credentialQuery
                .findCredentials(CredentialFindQuery(page = 0, take = 1, userId = command.userId))
                .first
                .firstOrNull() ?: throw InvalidCredentialsException()
        if (!passwordHasher.verify(command.password, credential.passwordHash)) throw InvalidCredentialsException()
        return authService.sign(credential.userId)
    }
}
```

`findCredentials` follows the `find<Noun>s` unification rule from root `repository-pattern.md` — instead of a dedicated single-item lookup method (`findByUserId`), it's handled with `CredentialFindQuery(take = 1, userId = ...)` + `.first.firstOrNull()` (the same pattern as Account/Card/Payment's `*Query`). The harness's `repository-naming` rule automatically catches reintroduction of a `find...By...`-shaped method.

Both `SignUpService`/`SignInService` are use cases for `POST` endpoints, so (per the Kotlin implementation criteria in cqrs-pattern.md) they live in `application/command/` — `SignInService` is not placed in `application/query/` just because it doesn't write to the DB (that directory is reserved for the `CredentialQuery` port).

---

## Issuing the JWT — AuthService

```kotlin
// auth/application/AuthService.kt
@Service
class AuthService(jwtProperties: JwtProperties) {
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    fun sign(userId: String): String =
        Jwts.builder()
            .subject(userId)                                     // only userId goes into the payload
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(key)
            .compact()
}
```

**Keep only the minimum information in the token payload.** Include only `userId` (subject) — never `email`/`role`/`permissions`. A JWT is only signed, not encrypted, so anyone can read it via base64 decoding, and roles/permissions can change after issuance, so caching them in the token means changes don't take effect immediately. Look up whatever user information is needed from the DB at request-processing time.

---

## Verifying the JWT — Spring Security Filter

Extends `OncePerRequestFilter` to verify the `Authorization: Bearer <token>` header and populate `SecurityContextHolder` with the authentication info.

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
            }   // on verification failure, leave the SecurityContext empty and continue to the next filter — SecurityConfig handles the 401/403
        }
        filterChain.doFilter(request, response)
    }
}
```

Kotlin's `runCatching { }` collapses `try { } catch (e: Exception) { }` into an expression — it fits well with this pattern of silently letting a verification failure fall through to "unauthenticated" state.

---

## SecurityConfig — distinguishing endpoints that require auth from those that don't

The Guard/Filter is applied **per path pattern**, not per class (the idiomatic Spring Security approach). To avoid the risk of missing an annotation on some method, keep the whitelist of paths to a minimum.

```kotlin
// auth/infrastructure/SecurityConfig.kt — actual code
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
                // With the STATELESS session + OncePerRequestFilter combination, an exception raised
                // while handling a request leads to a container /error forward, and
                // JwtAuthenticationFilter is not re-run on the error dispatch by default, so the
                // SecurityContext ends up empty and the response flips from 401/400 to 403 instead —
                // open /error as permitAll too so the original status code is passed through unchanged.
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)   // every other domain API requires authentication
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
    }
}
```

Defaulting to `anyRequest, authenticated` and explicitly opening only the whitelist is the Spring Security idiom that implements the root's warning that "method-level application carries a risk of omission" — you can't accidentally forget authentication when adding a new endpoint.

---

## Interface — AuthController

```kotlin
// auth/interfaces/rest/AuthController.kt — actual code
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

`SignUpRequest.password` validates a minimum length with `@field:Size(min = 8)` — a violation raises `MethodArgumentNotValidException`, which `GlobalExceptionHandler` converts to 400 `VALIDATION_FAILED` (see [error-handling.md](error-handling.md)). `AuthController` is on `SecurityConfig`'s whitelist (`/auth/sign-in`, `/auth/sign-up`), so it doesn't take an `Authentication` parameter.

---

## Error handling — INVALID_CREDENTIALS / USER_ID_ALREADY_EXISTS

```kotlin
// auth/domain/AuthException.kt
sealed class AuthException(message: String, val code: AuthErrorCode) : RuntimeException(message)

class InvalidCredentialsException :
    AuthException("The user ID or password is incorrect.", AuthErrorCode.INVALID_CREDENTIALS)
class UserIdAlreadyExistsException :
    AuthException("This user ID is already in use.", AuthErrorCode.USER_ID_ALREADY_EXISTS)
```

`common/GlobalExceptionHandler.kt` converts `InvalidCredentialsException` → 401, and every other `AuthException` (currently only `UserIdAlreadyExistsException`) → 400 — the same pattern as the Account/Card BC's `<Domain>NotFoundException` → 404, other `<Domain>Exception` → 400.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where authentication sits in the filter chain
- [error-handling.md](error-handling.md) — the 401 response format for auth failures
- [repository-pattern.md](repository-pattern.md), [cqrs-pattern.md](cqrs-pattern.md) — Repository/Query separation principles
