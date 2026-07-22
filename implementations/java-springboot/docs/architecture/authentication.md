# Authentication Pattern (Spring Boot / Spring Security)

> For the framework-agnostic principles, see the root [authentication.md](../../../../docs/architecture/authentication.md).

## State of the current example

Every endpoint in `account/interfaces/rest/AccountController.java` accepts only already-authenticated users via the `Authentication` parameter:

```java
// AccountController.java — actual code
@PostMapping("/{accountId}/deposit")
public TransactionResult deposit(
        Authentication authentication,
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    String requesterId = authentication.getName();   // subject claim of the JWT
    return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
}
```

`config/SecurityConfig.java` (the JWT `SecurityFilterChain`, Nimbus symmetric-key encoder/decoder) together with the `spring-boot-starter-security`/`spring-boot-starter-oauth2-resource-server` dependencies in `build.gradle` handle authentication — every request is only processed after its JWT in `Authorization: Bearer <token>` is verified. `AccountControllerE2ETest` also authenticates via `Authorization: Bearer <token>` rather than an `X-User-Id` header (using the `headersFor(ownerId)`/`tokenFor(ownerId)` helpers), and the check that a different owner querying the resource gets a 404 is performed against the actual authenticated `requesterId`.

**Sign-in performs real credential verification.** Through the `auth/domain/Credential` (userId + bcrypt-hashed passwordHash) Aggregate and the `PasswordHasher` (Technical Service), `POST /auth/sign-in` only issues a token after comparing against the stored hash. `POST /auth/sign-up` handles new registrations in the order: check for a duplicate user ID → hash the password → save. The rest of this document describes the entire structure in detail (layer placement, `SecurityConfig`, the sign-up/sign-in flow, and token payload design).

---

## Layer placement principle

**Authentication is handled only in the Interface layer.** The Domain/Application layers never depend on the authentication context.

```
Interface layer (Spring Security Filter + Controller): extract token → verify → store Authentication in SecurityContext
Application layer (XxxService): uses only plain values such as userId carried in Command/Query objects
Domain layer (Account, Money, ...): has no concept of authentication. Never imports Spring Security
```

Incorrect pattern — an Application Service parsing the token directly:

```java
// Forbidden — an Application Service handling the Authorization header/JWT directly
@Service
public class DepositService {
    public TransactionResult deposit(String bearerToken, DepositCommand command) {
        String userId = jwtDecoder.decode(bearerToken).getSubject();  // ← this is the Interface layer's responsibility
        ...
    }
}
```

Correct pattern — the Controller extracts only the userId from the already-authenticated `Authentication` and passes it in via a Command. This project does not define a custom `UserDetails`/`AppUserDetails`, and simply uses `Authentication.getName()` (the JWT's subject claim) as `requesterId` — since the payload carries no claims beyond the subject (see "Token payload design" below), a custom principal type isn't needed:

```java
// AccountController.java — actual code
@PostMapping("/{accountId}/deposit")
public TransactionResult deposit(
        Authentication authentication,
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    String requesterId = authentication.getName();
    return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
}
```

---

## Dependencies

```groovy
// build.gradle — actual code
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'  // JWT verification
```

---

## Security configuration — `SecurityFilterChain`

```java
// config/SecurityConfig.java — actual code
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                .csrf(CsrfConfigurer::disable)                       // stateless REST API
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/actuator/health/**", "/error", "/auth/sign-in", "/auth/sign-up")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    @Bean
    public SecretKeySpec jwtSecretKey(@Value("${jwt.secret}") String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKeySpec jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKeySpec jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
```

Applying `authorizeHttpRequests` **filter-chain-wide** is the Spring Security implementation of the root principle that "guards should be applied at the class/global level; applying them per-method risks omissions." Managing only a whitelist (`permitAll()`) as the exception is safer than attaching `@PreAuthorize` to individual methods, because the default for any newly added endpoint is "authentication required." `jwt.secret` is injected from Secrets Manager in the production profile by the `SecretsEnvironmentPostProcessor` described in [secret-manager.md](secret-manager.md).

**`/error` must also be included in permitAll.** When Bean Validation fails on an endpoint that doesn't require authentication (e.g. `/auth/sign-up`), the servlet container re-dispatches to `/error`, and Spring Boot re-applies the Security filter chain to this re-dispatch by default. If the original request was unauthenticated (and passed through via permitAll), the `SecurityContext` is empty at the time of the `/error` re-dispatch, so it hits `anyRequest().authenticated()` and the response gets swapped to 401 instead of the intended `400 VALIDATION_FAILED`. `AuthControllerE2ETest`, which verifies the password-length validation failure on `/auth/sign-up`, confirms that the response is the correct `400 VALIDATION_FAILED` rather than `401`.

---

## Sign-up/sign-in — the Credential Aggregate + PasswordHasher (Technical Service)

`auth/` has the same 4-layer structure as the other domains (`account/`, `card/`):

```
auth/
  domain/
    Credential.java             ← Aggregate Root (credentialId, userId, passwordHash, createdAt)
    CredentialFindQuery.java    ← record(page, take, userId)
    CredentialsWithCount.java   ← record(credentials, count) — the root's {orders, count} pattern
    CredentialRepository.java   ← write-only: saveCredential(Credential)
    AuthException.java          ← ErrorCode.INVALID_CREDENTIALS / USER_ID_ALREADY_EXISTS
  application/
    command/
      SignUpCommand.java / SignUpService.java   ← check for duplicate ID → hash → save
      SignInCommand.java / SignInService.java   ← look up hash → verify → issue token
    query/
      CredentialQuery.java       ← read-only: findCredentials(CredentialFindQuery)
    service/
      PasswordHasher.java        ← Technical Service interface (see domain-service.md)
  infrastructure/
    BCryptPasswordHasher.java    ← PasswordHasher implementation (Spring Security BCryptPasswordEncoder)
    persistence/
      CredentialJpaEntity.java / CredentialJpaRepository.java / CredentialMapper.java
      CredentialRepositoryImpl.java  ← implements both CredentialRepository and CredentialQuery
  interfaces/
    rest/
      AuthController.java, SignUpRequest.java, SignInRequest.java
```

**Password hashing follows the same Technical Service pattern as notification sending (`NotificationService`)** — an interface lives in `application/service/` and its implementation (`BCryptPasswordHasher`, `@Component`) lives in `infrastructure/`, so the Domain/Application layers never depend on a concrete library like `BCryptPasswordEncoder`.

**`CredentialQuery` is shared by both Command Services.** Since `Credential` is an immutable record that is never modified after sign-up, `CredentialRepository` (domain, write) only has `saveCredential` — all lookups go through `CredentialQuery` (application/query, read-only). `SignUpService` uses `CredentialQuery` to check for a duplicate ID, and `SignInService` uses it to look up the stored hash (the same separation of roles as `AccountQuery`/`CardQuery` in cqrs-pattern.md). `CredentialRepositoryImpl` implements both interfaces (the same structure as `AccountRepositoryImpl implements AccountRepository, AccountQuery`).

```java
// auth/interfaces/rest/AuthController.java — actual code
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignUpService signUpService;
    private final SignInService signInService;

    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    public void signUp(@Valid @RequestBody SignUpRequest request) {
        signUpService.signUp(new SignUpCommand(request.userId(), request.password()));
    }

    @PostMapping("/sign-in")
    public SignInResult signIn(@Valid @RequestBody SignInRequest request) {
        return signInService.signIn(new SignInCommand(request.userId(), request.password()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e) {
        HttpStatus status = e.code() == AuthException.ErrorCode.INVALID_CREDENTIALS
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
```

```java
// auth/application/command/SignUpService.java — actual code
@Service
@RequiredArgsConstructor
public class SignUpService {

    private final CredentialQuery credentialQuery;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;

    public void signUp(SignUpCommand command) {
        boolean exists = !credentialQuery
                .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                .credentials().isEmpty();
        if (exists) {
            throw new AuthException(AuthException.ErrorCode.USER_ID_ALREADY_EXISTS, "This user ID is already in use.");
        }

        String passwordHash = passwordHasher.hash(command.password());
        Credential credential = Credential.create(command.userId(), passwordHash);
        credentialRepository.saveCredential(credential);
    }
}
```

```java
// auth/application/command/SignInService.java — actual code
@Service
@RequiredArgsConstructor
public class SignInService {

    private final CredentialQuery credentialQuery;
    private final PasswordHasher passwordHasher;
    private final JwtEncoder jwtEncoder;

    // A missing user ID and a wrong password respond with the same error code/message (INVALID_CREDENTIALS) —
    // distinguishing between them would let an attacker guess which user IDs exist (user enumeration).
    public SignInResult signIn(SignInCommand command) {
        Credential credential = credentialQuery
                .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                .credentials().stream().findFirst()
                .orElseThrow(() -> new AuthException(
                        AuthException.ErrorCode.INVALID_CREDENTIALS, "Invalid user ID or password."));

        if (!passwordHasher.verify(command.password(), credential.getPasswordHash())) {
            throw new AuthException(
                    AuthException.ErrorCode.INVALID_CREDENTIALS, "Invalid user ID or password.");
        }

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(credential.getUserId())    // ← payload is kept minimal: just userId
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}
```

`BCryptPasswordHasher` uses `BCryptPasswordEncoder(12)` (strength 12) — this is already included in `spring-boot-starter-security`, so no new dependency is needed. The sign-up password's minimum length is validated with `@Size(min = 8)` on `SignUpRequest`.

---

## Token verification — handled automatically by the Resource Server

Spring Security's `oauth2ResourceServer().jwt()` automatically extracts the `Authorization: Bearer <token>` header, verifies the signature, and checks expiration, all within the filter chain. There's no need to implement a separate `AuthGuard`/`Filter` by hand — the standard Spring Security filter plays the role that NestJS's `AuthGuard` would.

Once verification succeeds, `SecurityContextHolder` is populated with an `Authentication`, and the Controller extracts it via the `Authentication` parameter:

```java
// AccountController.java — actual code
@GetMapping("/{accountId}")
public GetAccountResult getAccount(Authentication authentication, @PathVariable String accountId) {
    String userId = authentication.getName();   // subject claim of the JWT
    return getAccountService.getAccount(accountId, userId);
}
```

---

## Token payload design

The JWT claims carry only `userId` (subject). Roles, email, and similar data are not included — because the payload is signed but not encrypted (anyone can read it via `base64url` decoding), putting frequently-changing or sensitive information in it means changes won't take effect until the token is reissued, or the information gets exposed. If roles/permissions are needed, look them up from the DB using `userId` at request-handling time. The actual `SignInService` follows this principle — it puts only `subject(credential.getUserId())` in the claims.

```java
// Correct approach (this is the pattern the actual code uses)
JwtClaimsSet.builder().subject(credential.getUserId()).issuedAt(now).expiresAt(exp).build()

// Incorrect approach — includes sensitive/mutable information
JwtClaimsSet.builder().subject(user.getUserId()).claim("email", user.getEmail())
        .claim("role", user.getRole()).claim("permissions", user.getPermissions()).build()
```

---

## Components of the authentication/authorization pipeline

The full authentication/authorization pipeline consists of the following components:

1. `SecurityConfig` handles JWT verification, and `AuthController`/`SignUpService`/`SignInService` provide the sign-up/sign-in endpoints.
2. Every endpoint in `AccountController` accepts an `Authentication authentication` and uses `authentication.getName()`.
3. `tokenFor(ownerId)` in `AccountControllerE2ETest`/`CardControllerE2ETest`/`NotificationE2ETest` calls `/auth/sign-up` → `/auth/sign-in` in sequence to obtain and cache a test JWT.
4. Only `/health/**`, `/error`, `/auth/sign-in`, and `/auth/sign-up` are authentication exceptions; every other endpoint requires authentication by default (`anyRequest().authenticated()` in `SecurityConfig`).
5. Credential verification for sign-in itself is implemented for real via `Credential`/`PasswordHasher` (see the "Sign-up/sign-in" section above).

The entire authentication/authorization pipeline — from token issuance through verification to credential verification — is wired correctly.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where the authentication filter sits in the request pipeline
- [layer-architecture.md](layer-architecture.md) — the Interface layer's responsibilities
- [secret-manager.md](secret-manager.md) — JWT secret management
