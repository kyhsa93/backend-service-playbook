# 인증 패턴 (Spring Boot / Spring Security)

> 프레임워크 무관 원칙은 루트 [authentication.md](../../../../docs/architecture/authentication.md) 참고.

## 현재 예제의 상태 — 적용 완료

`account/interfaces/rest/AccountController.java`의 모든 엔드포인트는 `Authentication` 파라미터로 이미 인증된 사용자만 받는다:

```java
// AccountController.java — 실제 코드
@PostMapping("/{accountId}/deposit")
public TransactionResult deposit(
        Authentication authentication,
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    String requesterId = authentication.getName();   // JWT의 subject claim
    return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
}
```

`config/SecurityConfig.java`(JWT `SecurityFilterChain`, Nimbus 대칭키 인코더/디코더)와 `build.gradle`의 `spring-boot-starter-security`/`spring-boot-starter-oauth2-resource-server` 의존성이 이미 존재한다 — 더 이상 "X-User-Id 헤더를 그대로 신뢰"하는 상태가 아니다. `AccountControllerE2ETest`도 `X-User-Id` 헤더 대신 `Authorization: Bearer <token>`으로 인증하며(`headersFor(ownerId)`/`tokenFor(ownerId)` 헬퍼), 다른 소유자가 조회하면 404를 반환하는 검증은 실제로 인증된 `requesterId`를 기준으로 이루어진다.

**로그인도 실제 자격증명 검증을 거친다.** `auth/domain/Credential`(userId + bcrypt 해시된 passwordHash) Aggregate와 `PasswordHasher`(Technical Service) 도입으로, `POST /auth/sign-in`은 저장된 해시와 비교한 뒤에만 토큰을 발급한다 — 과거에는 주어진 `userId`만으로 서명된 토큰을 발급해 다른 사용자를 사칭할 수 있는 취약점이 있었다(5개 언어 공통 CRITICAL 감사 발견 사항, nestjs 구현체에서 먼저 수정됨). `POST /auth/sign-up`으로 신규 가입(아이디 중복 확인 → 비밀번호 해싱 → 저장)도 새로 추가되었다. 아래에서 이 구조 전체(레이어 배치, `SecurityConfig`, 가입/로그인 흐름, 토큰 payload 설계)를 상세히 설명한다.

---

## 레이어 배치 원칙

**인증은 Interface 레이어에서만 처리한다.** Domain/Application 레이어는 인증 컨텍스트에 의존하지 않는다.

```
Interface 레이어 (Spring Security Filter + Controller): 토큰 추출 → 검증 → SecurityContext에 Authentication 저장
Application 레이어 (XxxService): Command/Query 객체에 담긴 userId 등 값만 사용
Domain 레이어 (Account, Money, ...): 인증 개념 없음. Spring Security를 import하지 않는다
```

잘못된 패턴 — Application Service에서 토큰을 직접 파싱:

```java
// 금지 — Application Service가 Authorization 헤더/JWT를 직접 다룸
@Service
public class DepositService {
    public TransactionResult deposit(String bearerToken, DepositCommand command) {
        String userId = jwtDecoder.decode(bearerToken).getSubject();  // ← Interface 레이어 책임
        ...
    }
}
```

올바른 패턴 — Controller가 이미 인증된 `Authentication`에서 userId만 꺼내 Command에 담아 전달한다. 이 저장소는 커스텀 `UserDetails`/`AppUserDetails`를 두지 않고 `Authentication.getName()`(JWT의 subject claim)을 그대로 `requesterId`로 사용한다 — payload에 subject 외의 클레임을 담지 않으므로(아래 "토큰 payload 설계" 참고) 커스텀 principal 타입이 굳이 필요 없다:

```java
// AccountController.java — 실제 코드
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

## 의존성 — 이미 추가됨

```groovy
// build.gradle — 실제 코드
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'  // JWT 검증
```

---

## Security 설정 — `SecurityFilterChain` (이미 구현됨)

```java
// config/SecurityConfig.java — 실제 코드
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

`authorizeHttpRequests`를 **필터 체인 전역**에 적용하는 것이 루트가 강조하는 "Guard는 클래스/전역 레벨에서 적용, 메서드별 적용은 누락 위험" 원칙의 Spring Security식 구현이다. `@PreAuthorize`를 개별 메서드에 붙이는 방식보다 화이트리스트(`permitAll()`)만 예외로 관리하는 편이 안전하다 — 새 엔드포인트를 추가할 때 기본값이 "인증 필요"이기 때문이다. `jwt.secret`은 [secret-manager.md](secret-manager.md)의 `SecretsEnvironmentPostProcessor`가 운영 프로필에서 Secrets Manager로부터 주입한다.

**`/error`도 permitAll에 포함해야 한다.** 인증이 필요 없는 엔드포인트(`/auth/sign-up` 등)에서 Bean Validation이 실패하면 서블릿 컨테이너가 `/error`로 재디스패치하는데, Spring Boot는 기본적으로 이 재디스패치에도 Security 필터 체인을 다시 적용한다. 원 요청이 인증되지 않은 상태였다면(permitAll로 통과) `/error` 재디스패치 시점에는 `SecurityContext`가 비어 있어 `anyRequest().authenticated()`에 걸려 401로 응답이 뒤바뀐다 — 원래 응답이어야 할 `400 VALIDATION_FAILED`가 아니라 엉뚱한 `401`이 나가는 오탐이었다. `/auth/sign-up`의 비밀번호 길이 검증 실패를 검증하는 `AuthControllerE2ETest`가 이 문제를 실제로 잡아냈다.

---

## 가입/로그인 — Credential Aggregate + PasswordHasher(Technical Service)

`auth/` 아래에 다른 도메인(`account/`, `card/`)과 동일한 4레이어 구조가 있다:

```
auth/
  domain/
    Credential.java             ← Aggregate Root (credentialId, userId, passwordHash, createdAt)
    CredentialFindQuery.java    ← record(page, take, userId)
    CredentialsWithCount.java   ← record(credentials, count) — root의 {orders, count} 패턴
    CredentialRepository.java   ← 쓰기 전용: saveCredential(Credential)
    AuthException.java          ← ErrorCode.INVALID_CREDENTIALS / USER_ID_ALREADY_EXISTS
  application/
    command/
      SignUpCommand.java / SignUpService.java   ← 아이디 중복 확인 → 해싱 → 저장
      SignInCommand.java / SignInService.java   ← 해시 조회 → 검증 → 토큰 발급
    query/
      CredentialQuery.java       ← 읽기 전용: findCredentials(CredentialFindQuery)
    service/
      PasswordHasher.java        ← Technical Service 인터페이스 (domain-service.md 참고)
  infrastructure/
    BCryptPasswordHasher.java    ← PasswordHasher 구현체 (Spring Security BCryptPasswordEncoder)
    persistence/
      CredentialJpaEntity.java / CredentialJpaRepository.java / CredentialMapper.java
      CredentialRepositoryImpl.java  ← CredentialRepository + CredentialQuery 동시 구현
  interfaces/
    rest/
      AuthController.java, SignUpRequest.java, SignInRequest.java
```

**비밀번호 해싱은 알림 발송(`NotificationService`)과 동일한 Technical Service 패턴이다** — `application/service/`에 인터페이스, `infrastructure/`에 구현체(`BCryptPasswordHasher`, `@Component`)를 두어 Domain/Application이 `BCryptPasswordEncoder` 같은 구체 라이브러리에 의존하지 않게 한다.

**`CredentialQuery`를 두 Command Service가 함께 쓴다.** `Credential`은 가입(`sign-up`) 이후 수정되지 않는 불변 레코드이므로 `CredentialRepository`(domain, 쓰기)에는 `saveCredential`만 있다 — 조회는 전부 `CredentialQuery`(application/query, 읽기 전용)를 거친다. `SignUpService`는 아이디 중복 확인에, `SignInService`는 저장된 해시 조회에 각각 `CredentialQuery`를 사용한다(cqrs-pattern.md의 `AccountQuery`/`CardQuery`와 동일한 역할 분리). `CredentialRepositoryImpl`이 두 인터페이스를 모두 구현한다(`AccountRepositoryImpl implements AccountRepository, AccountQuery`와 동일한 구조).

```java
// auth/interfaces/rest/AuthController.java — 실제 코드
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
// auth/application/command/SignUpService.java — 실제 코드
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
            throw new AuthException(AuthException.ErrorCode.USER_ID_ALREADY_EXISTS, "이미 사용 중인 아이디입니다.");
        }

        String passwordHash = passwordHasher.hash(command.password());
        Credential credential = Credential.create(command.userId(), passwordHash);
        credentialRepository.saveCredential(credential);
    }
}
```

```java
// auth/application/command/SignInService.java — 실제 코드
@Service
@RequiredArgsConstructor
public class SignInService {

    private final CredentialQuery credentialQuery;
    private final PasswordHasher passwordHasher;
    private final JwtEncoder jwtEncoder;

    // 아이디 미존재와 비밀번호 불일치를 동일한 에러 코드/메시지(INVALID_CREDENTIALS)로 응답한다 —
    // 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration).
    public SignInResult signIn(SignInCommand command) {
        Credential credential = credentialQuery
                .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                .credentials().stream().findFirst()
                .orElseThrow(() -> new AuthException(
                        AuthException.ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordHasher.verify(command.password(), credential.getPasswordHash())) {
            throw new AuthException(
                    AuthException.ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(credential.getUserId())    // ← payload는 최소한만: userId 하나
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}
```

`BCryptPasswordHasher`는 `BCryptPasswordEncoder(12)`(strength 12)를 사용한다 — `spring-boot-starter-security`에 이미 포함되어 있어 새 의존성이 필요 없다. 회원가입 비밀번호는 `SignUpRequest`에서 `@Size(min = 8)`로 최소 길이를 검증한다.

---

## 토큰 검증 — Resource Server가 자동 처리

Spring Security의 `oauth2ResourceServer().jwt()`는 `Authorization: Bearer <token>` 헤더 추출, 서명 검증, 만료 확인을 필터 체인에서 자동으로 수행한다. 별도 `AuthGuard`/`Filter`를 직접 구현할 필요가 없다 — NestJS의 `AuthGuard`에 해당하는 역할을 Spring Security 표준 필터가 대신한다.

검증에 성공하면 `SecurityContextHolder`에 `Authentication`이 채워지고, Controller는 `Authentication` 파라미터로 이를 꺼낸다:

```java
// AccountController.java — 실제 코드
@GetMapping("/{accountId}")
public GetAccountResult getAccount(Authentication authentication, @PathVariable String accountId) {
    String userId = authentication.getName();   // JWT의 subject claim
    return getAccountService.getAccount(accountId, userId);
}
```

---

## 토큰 payload 설계

JWT claim에는 `userId`(subject)만 담는다. 역할(role)/이메일 등은 담지 않는다 — payload는 서명만 되고 암호화되지 않으므로(`base64url` 디코딩으로 누구나 읽을 수 있음), 자주 바뀌거나 민감한 정보를 넣으면 토큰 재발급 전까지 변경이 반영되지 않거나 정보가 노출된다. 역할/권한이 필요하면 요청 처리 시점에 `userId`로 DB에서 조회한다. 실제 `SignInService`가 이미 이 원칙을 따른다 — `subject(credential.getUserId())` 하나만 claim에 담는다.

```java
// 올바른 방식 (실제 코드가 이미 이 패턴)
JwtClaimsSet.builder().subject(credential.getUserId()).issuedAt(now).expiresAt(exp).build()

// 잘못된 방식 — 민감/가변 정보 포함
JwtClaimsSet.builder().subject(user.getUserId()).claim("email", user.getEmail())
        .claim("role", user.getRole()).claim("permissions", user.getPermissions()).build()
```

---

## `X-User-Id`에서 JWT로 — 마이그레이션 완료

과거 `AccountController`/`AccountControllerE2ETest`가 `X-User-Id` 헤더에 의존하던 상태에서 위 구조로 이미 전환되었다:

1. `SecurityConfig` 추가 완료, `AuthController`/`SignUpService`/`SignInService`로 가입/로그인 엔드포인트 신설 완료.
2. `AccountController`의 모든 엔드포인트가 `@RequestHeader("X-User-Id") String requesterId` 대신 `Authentication authentication`을 받고 `authentication.getName()`을 사용한다.
3. `AccountControllerE2ETest`/`CardControllerE2ETest`/`NotificationE2ETest`의 `tokenFor(ownerId)`가 `/auth/sign-up` → `/auth/sign-in` 순서로 호출해 테스트용 JWT를 발급받아 캐싱한다.
4. `/health/**`, `/error`, `/auth/sign-in`, `/auth/sign-up`만 인증 예외이고 나머지 전체 엔드포인트는 기본적으로 인증을 요구한다(`SecurityConfig`의 `anyRequest().authenticated()`).
5. 로그인 자체의 자격증명 검증도 `Credential`/`PasswordHasher`로 실제 구현되었다(위 "가입/로그인" 절 참고) — 더 이상 알려진 gap이 아니다.

인증/인가 파이프라인 전체(토큰 발급부터 검증, 자격증명 검증까지)가 올바르게 배선되어 있다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 필터 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [secret-manager.md](secret-manager.md) — JWT secret 관리
