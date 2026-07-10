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

**단, 로그인 자체는 자격증명을 검증하지 않는 의도적 단순화다.** `auth/application/command/SignInService`는 별도의 User/비밀번호 저장소 없이, 주어진 `userId`에 대해 서명만 검증되고 검증되지 않은 자격증명으로 토큰을 발급한다 — go/nestjs 구현체와 동일한 단순화이며, `harness/`와 `examples/`는 이번 문서화 패스에서 변경하지 않는다. 아래에서 이 구조 전체(레이어 배치, `SecurityConfig`, 로그인 흐름, 토큰 payload 설계)를 상세히 설명한다.

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
                        .requestMatchers("/health/**", "/auth/sign-in").permitAll()
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

---

## 토큰 발급 — 로그인 (자격증명 검증 없는 단순화)

```java
// auth/interfaces/rest/AuthController.java — 실제 코드
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignInService signInService;

    @PostMapping("/sign-in")
    public SignInResult signIn(@Valid @RequestBody SignInRequest request) {
        return signInService.signIn(new SignInCommand(request.userId()));
    }
}
```

```java
// auth/application/command/SignInService.java — 실제 코드
@Service
@RequiredArgsConstructor
public class SignInService {

    private final JwtEncoder jwtEncoder;

    // 이 저장소에는 별도의 User/자격증명 저장소가 없다 — 자격증명 검증 없이 주어진 userId로
    // 토큰을 발급한다(nestjs/go 구현과 동일한 단순화). payload는 최소한(userId/subject)만 담는다.
    public SignInResult signIn(SignInCommand command) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(command.userId())          // ← payload는 최소한만: userId 하나
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}
```

실제 서비스에서는 `signIn()`이 `UserRepository`/`PasswordEncoder`로 자격증명을 검증한 뒤에만 토큰을 발급해야 한다 — 이 저장소는 Account 도메인 자체에 집중하기 위해 그 부분을 의도적으로 생략했다(go/nestjs 구현체와 동일). 실제 프로덕션에 반영할 때는 `signIn()` 내부에 자격증명 검증 단계를 추가해야 한다.

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

JWT claim에는 `userId`(subject)만 담는다. 역할(role)/이메일 등은 담지 않는다 — payload는 서명만 되고 암호화되지 않으므로(`base64url` 디코딩으로 누구나 읽을 수 있음), 자주 바뀌거나 민감한 정보를 넣으면 토큰 재발급 전까지 변경이 반영되지 않거나 정보가 노출된다. 역할/권한이 필요하면 요청 처리 시점에 `userId`로 DB에서 조회한다. 실제 `SignInService`가 이미 이 원칙을 따른다 — `subject(command.userId())` 하나만 claim에 담는다.

```java
// 올바른 방식 (실제 코드가 이미 이 패턴)
JwtClaimsSet.builder().subject(command.userId()).issuedAt(now).expiresAt(exp).build()

// 잘못된 방식 — 민감/가변 정보 포함
JwtClaimsSet.builder().subject(user.getUserId()).claim("email", user.getEmail())
        .claim("role", user.getRole()).claim("permissions", user.getPermissions()).build()
```

---

## `X-User-Id`에서 JWT로 — 마이그레이션 완료

과거 `AccountController`/`AccountControllerE2ETest`가 `X-User-Id` 헤더에 의존하던 상태에서 위 구조로 이미 전환되었다:

1. `SecurityConfig` 추가 완료, `AuthController`/`SignInService`로 로그인 엔드포인트 신설 완료.
2. `AccountController`의 모든 엔드포인트가 `@RequestHeader("X-User-Id") String requesterId` 대신 `Authentication authentication`을 받고 `authentication.getName()`을 사용한다.
3. `AccountControllerE2ETest`의 `headersFor(ownerId)`가 `Authorization: Bearer <token>`을 설정하며, `tokenFor(ownerId)`가 `/auth/sign-in` 호출로 테스트용 JWT를 발급받아 캐싱한다.
4. `/health/**`, `/auth/sign-in`만 인증 예외이고 나머지 전체 엔드포인트는 기본적으로 인증을 요구한다(`SecurityConfig`의 `anyRequest().authenticated()`).

남은 gap은 로그인 자체의 자격증명 검증 부재뿐이다(위 "토큰 발급" 절 참고) — 인증/인가 파이프라인 구조 자체는 이미 올바르게 배선되어 있다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 필터 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [secret-manager.md](secret-manager.md) — JWT secret 관리
