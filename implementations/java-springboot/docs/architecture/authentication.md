# 인증 패턴 (Spring Boot / Spring Security)

> 프레임워크 무관 원칙은 루트 [authentication.md](../../../../docs/architecture/authentication.md) 참고.

## 현재 예제의 상태 — 알려진 gap

`account/interfaces/rest/AccountController.java`의 모든 엔드포인트는 인증을 하지 않는다:

```java
@PostMapping("/{accountId}/deposit")
public TransactionResult deposit(
        @RequestHeader("X-User-Id") String requesterId,   // ← 클라이언트가 보낸 값을 그대로 신뢰
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) { ... }
```

`X-User-Id` 헤더는 서명도 검증도 없는 클라이언트 제공 값이다. 누구든 이 헤더에 임의의 값을 넣어 다른 사용자의 계좌에 접근할 수 있다 — E2E 테스트(`AccountControllerE2ETest`)가 "다른 소유자가 조회하면 404"를 검증하는 것도 실은 `ownerId` 필터링 로직만 검증할 뿐, 그 `ownerId`(`requesterId`) 자체가 인증되지 않았다는 근본 문제는 남는다. 이 문서는 **`build.gradle`에 Spring Security 의존성이 없는 현재 상태에서 실제로 추가해야 하는 올바른 패턴**을 설명한다. `harness/`와 `examples/`는 이번 문서화 패스에서 변경하지 않는다.

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

올바른 패턴 — Controller가 이미 인증된 `Authentication`에서 userId만 꺼내 Command에 담아 전달:

```java
@PostMapping("/{accountId}/deposit")
public TransactionResult deposit(
        @AuthenticationPrincipal AppUserDetails principal,
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    return depositService.deposit(new DepositCommand(accountId, principal.userId(), request.amount()));
}
```

---

## 의존성 추가

```groovy
// build.gradle
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'  // JWT 검증
```

---

## Security 설정 — `SecurityFilterChain`

```java
// config/SecurityConfig.java
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
}
```

`authorizeHttpRequests`를 **필터 체인 전역**에 적용하는 것이 루트가 강조하는 "Guard는 클래스/전역 레벨에서 적용, 메서드별 적용은 누락 위험" 원칙의 Spring Security식 구현이다. `@PreAuthorize`를 개별 메서드에 붙이는 방식보다 화이트리스트(`permitAll()`)만 예외로 관리하는 편이 안전하다 — 새 엔드포인트를 추가할 때 기본값이 "인증 필요"이기 때문이다.

---

## 토큰 발급 — 로그인

```java
// auth/interfaces/rest/AuthController.java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignInService signInService;

    @PostMapping("/sign-in")
    public SignInResult signIn(@Valid @RequestBody SignInRequest request) {
        return signInService.signIn(new SignInCommand(request.email(), request.password()));
    }
}
```

```java
// auth/application/command/SignInService.java
@Service
@RequiredArgsConstructor
public class SignInService {

    private final JwtEncoder jwtEncoder;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public SignInResult signIn(SignInCommand command) {
        User user = userRepository.findByEmail(command.email())
                .filter(u -> passwordEncoder.matches(command.password(), u.getPasswordHash()))
                .orElseThrow(() -> new AuthException(AuthException.ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 올바르지 않습니다."));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(user.getUserId())          // ← payload는 최소한만: userId 하나
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new SignInResult(token);
    }
}
```

`JwtEncoder`/`JwtDecoder` 빈은 대칭키(`NimbusJwtEncoder` + `SecretKeySpec`) 또는 비대칭키(RSA)로 구성한다. 로컬 개발/JWT secret은 [secret-manager.md](secret-manager.md)의 SecretService로 조회한다.

---

## 토큰 검증 — Resource Server가 자동 처리

Spring Security의 `oauth2ResourceServer().jwt()`는 `Authorization: Bearer <token>` 헤더 추출, 서명 검증, 만료 확인을 필터 체인에서 자동으로 수행한다. 별도 `AuthGuard`/`Filter`를 직접 구현할 필요가 없다 — NestJS의 `AuthGuard`에 해당하는 역할을 Spring Security 표준 필터가 대신한다.

검증에 성공하면 `SecurityContextHolder`에 `Authentication`이 채워지고, Controller는 `@AuthenticationPrincipal` 또는 `Authentication` 파라미터로 이를 꺼낸다:

```java
@GetMapping("/{accountId}")
public GetAccountResult getAccount(Authentication authentication, @PathVariable String accountId) {
    String userId = authentication.getName();   // JWT의 subject claim
    return getAccountService.getAccount(accountId, userId);
}
```

---

## 토큰 payload 설계

JWT claim에는 `userId`(subject)만 담는다. 역할(role)/이메일 등은 담지 않는다 — payload는 서명만 되고 암호화되지 않으므로(`base64url` 디코딩으로 누구나 읽을 수 있음), 자주 바뀌거나 민감한 정보를 넣으면 토큰 재발급 전까지 변경이 반영되지 않거나 정보가 노출된다. 역할/권한이 필요하면 요청 처리 시점에 `userId`로 DB에서 조회한다.

```java
// 올바른 방식
JwtClaimsSet.builder().subject(user.getUserId()).issuedAt(now).expiresAt(exp).build()

// 잘못된 방식 — 민감/가변 정보 포함
JwtClaimsSet.builder().subject(user.getUserId()).claim("email", user.getEmail())
        .claim("role", user.getRole()).claim("permissions", user.getPermissions()).build()
```

---

## 마이그레이션 경로 — `X-User-Id`에서 JWT로

현재 `AccountController`/`AccountControllerE2ETest`가 `X-User-Id` 헤더에 의존하는 모든 지점을 한 번에 바꿔야 하므로, 실제 적용 시에는:

1. `SecurityConfig` 추가, `AuthController`/`SignInService`로 로그인 엔드포인트 신설.
2. `AccountController`의 `@RequestHeader("X-User-Id") String requesterId` 파라미터를 `Authentication authentication`(또는 `@AuthenticationPrincipal`)으로 교체하고 `authentication.getName()`을 `requesterId` 자리에 사용.
3. E2E 테스트의 `headersFor(ownerId)`가 `X-User-Id` 대신 `Authorization: Bearer <testToken>`을 설정하도록 갱신하고, 테스트용 JWT 발급 헬퍼를 추가.
4. `/health/**`, `/auth/sign-in`만 인증 예외로 남기고 나머지 전체 엔드포인트는 기본적으로 인증을 요구하도록 한다.

이 마이그레이션 자체는 `examples/` 코드 변경이 필요하므로 이번 문서화 패스의 범위 밖이다 — 후속 이슈로 트래킹한다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 필터 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [secret-manager.md](secret-manager.md) — JWT secret 관리
