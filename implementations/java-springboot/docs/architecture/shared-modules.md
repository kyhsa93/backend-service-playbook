# 공유 코드 구조 (Spring Boot)

> NestJS 대비 문서. NestJS의 `src/common/`/`src/database/`/`src/outbox/`/`src/auth/`(각각 별도 `@Module`)에 대응하되, Spring Boot는 "공유 모듈"이라는 별도 컨테이너 단위가 없다 — [module-pattern.md](module-pattern.md)에서 설명하듯 패키지 자체가 곧 관례상 경계다.

## 현재 실제 상태 — `common/`/`config/`/`outbox/`/`auth/` 모두 존재

`examples/src/main/java/com/example/accountservice/` 트리 전체를 확인한 결과, 최상위 패키지는 `account/`(1번째 도메인), `card/`(2번째 도메인), `auth/`(인증/가입), `common/`, `config/`, `outbox/`다. `notification`(Technical Service)은 `account`만 사용하므로 최상위가 아니라 `account/` 내부(`account/application/service/`, `account/infrastructure/notification/`)에 있다 — 공유되지 않는 코드를 미리 공용 패키지로 끌어올리지 않는다는 원칙의 실제 예다.

여러 도메인이 실제로 공유해 최상위에 있는 패키지는 `common/`(`IdGenerator`, `web/`의 Filter/Interceptor, `SecretService`), `config/`(`@ConfigurationProperties` record들), `outbox/`(`OutboxEvent`/`OutboxWriter`/`OutboxRelay`/`OutboxEventHandler`)다. `database/`(여러 Repository를 하나의 트랜잭션으로 묶는 유틸)만 아직 없다 — [layer-architecture.md](layer-architecture.md)가 설명하듯 그런 트랜잭션 전파가 필요한 시나리오가 아직 없기 때문이다.

상세는 [directory-structure.md](directory-structure.md)의 실제 트리를 참고한다 — 이 문서는 그 배치를 NestJS의 공유 모듈 구조와 대응시켜 설명한다.

---

## 실제 배치

```
com.example.accountservice/
  common/                  # 프레임워크 무의존 순수 유틸 + Interface 레이어 공용 부품
    IdGenerator.java        # aggregate-id.md 참고 — 순수 Java, 어떤 프레임워크도 import 안 함
    web/
      GlobalExceptionHandler.java   # @RestControllerAdvice, error-handling.md 참고
      CorrelationIdFilter.java      # cross-cutting-concerns.md 참고
      RequestLoggingInterceptor.java
      RateLimitFilter.java
    service/
      SecretService.java            # 인터페이스 — secret-manager.md 참고
    infrastructure/
      SecretServiceImpl.java        # TTL 캐시 포함 구현체
    config/
      SecretsEnvironmentPostProcessor.java   # secret-manager.md 참고, prod 프로필에서 jwt.secret 주입

  config/                  # @ConfigurationProperties record 전용 (common/config와 역할 구분)
    AwsProperties.java       # config.md 참고
    SesProperties.java
    JwtProperties.java
    SecurityConfig.java      # @Configuration, JWT SecurityFilterChain — authentication.md 참고
    WebConfig.java           # Filter/Interceptor 등록 — cross-cutting-concerns.md 참고

  outbox/                   # domain-events.md 참고
    OutboxEvent.java          # @Entity — Outbox 테이블 매핑
    OutboxEventJpaRepository.java
    OutboxEventHandler.java   # 이벤트 타입별 Handler가 구현하는 인터페이스
    OutboxWriter.java         # Repository.save() 트랜잭션 안에서 이벤트를 Outbox 행으로 적재
    OutboxRelay.java          # Command Service가 저장 직후 동기 호출 — @Scheduled 폴링 아님

  auth/                     # 인증/가입 — authentication.md 참고
    domain/                   # Credential Aggregate(userId + bcrypt 해시)
    application/              # SignInService/SignUpService
    infrastructure/           # BCryptPasswordHasher, CredentialRepositoryImpl
    interfaces/rest/           # AuthController

  account/                  # 1번째 도메인
    domain/ application/ infrastructure/ interfaces/
    application/service/NotificationService.java        # 도메인 스코프 Technical Service
    infrastructure/notification/                          # 구현체 — account만 사용, 공유 패키지 아님

  card/                     # 2번째 도메인 — account와 Integration Event로 통신
    domain/ application/ infrastructure/ interfaces/

  AccountServiceApplication.java
```

여러 도메인/`Technical Service`가 두루 쓰는 항목만 최상위(`common/`, `config/`, `outbox/`)에 있고, `notification`처럼 한 도메인만 쓰는 Technical Service는 그 도메인 내부에 남는다 — `database/`(여러 Repository를 하나의 트랜잭션으로 묶는 유틸)만 그런 시나리오가 아직 없어 만들지 않았다.

**`common/`과 `config/`를 나눈 이유**: `common/`은 도메인 무관 **범용 유틸/Interface 레이어 부품**(ID 생성, 전역 예외 처리, 필터, Secret 조회)이고, `config/`는 **`@ConfigurationProperties` 바인딩 전용 record + Security/Web 설정**([config.md](config.md) 참고)이다. 후자는 Infrastructure 레이어에서만 주입받는다는 제약이 있어 역할이 명확히 다르므로 분리했다.

---

## NestJS 공유 모듈과의 대응

| NestJS (`@Module`, `@Global`) | Spring Boot 대응 | 이 저장소의 상태 |
|---|---|---|
| `src/common/` (필터, 인터셉터, 유틸) | `common/` 패키지 — `@Component`/순수 유틸 혼재 | 있음 — `IdGenerator`, `web/`의 Filter·Interceptor, `SecretService`(secret-manager.md 참고) |
| `src/database/` (`@Global` — DataSource, TransactionManager) | `database/` 패키지 — 단, Spring은 `@Global` 개념 자체가 불필요 | 없음 (JPA `DataSource`는 auto-configuration이 이미 전역 제공, 여러 Repository를 묶는 트랜잭션 유틸도 필요한 시나리오가 아직 없음 — layer-architecture.md 참고) |
| `src/outbox/` (`@Global` — OutboxWriter/Relay/Consumer) | `outbox/` 패키지 | 있음 — `OutboxWriter`/`OutboxRelay`/`OutboxEventHandler`(domain-events.md 참고) |
| `src/auth/` (인증 공유 모듈) | `auth/` 패키지 | 있음 — `Credential` Aggregate + `SignInService`/`SignUpService`(authentication.md 참고) |

**`@Global` 데코레이터가 Spring에는 없는 이유**: NestJS는 기본적으로 모듈 스코프가 닫혀 있어 `@Global`로 명시해야 모든 모듈에서 `imports` 없이 주입 가능해진다. Spring Boot는 애초에 전역 스코프가 기본값이다([module-pattern.md](module-pattern.md) "근본적 차이" 참고) — `database/`에 `DataSource` `@Bean`을 두면 별도 표시 없이 이미 어디서든 주입 가능하다. 즉 Spring에서는 "공유 모듈"이 NestJS처럼 특별한 선언을 요구하는 개념이 아니라, **그냥 패키지를 나누는 정리 방식**일 뿐이다.

---

## 공유 코드와 도메인 코드를 나누는 기준

- **두 개 이상의 도메인/Technical Service가 참조하면 공유 코드다.** `IdGenerator`는 `account`/`card`/`auth`가 모두 쓰는 유틸이므로 `common/`에 둔다. 반대로 `NotificationServiceImpl`은 `account` 도메인 하나만 쓰므로 최상위 공유 패키지가 아니라 `account/infrastructure/notification/`에 남는다.
- **공유 코드도 레이어 규율을 그대로 따른다.** `common/IdGenerator`는 어떤 프레임워크도 import하지 않는 순수 유틸이라 Domain 레이어(`Account.create()`)에서 직접 호출할 수 있다. 반면 `common/web/GlobalExceptionHandler`는 Spring MVC 타입(`ResponseEntity`, `@RestControllerAdvice`)에 의존하므로 Interface 레이어 성격의 공유 코드이고, Domain/Application에서 참조해서는 안 된다.
- **여러 도메인이 실제로 공유하게 될 때만 최상위로 끌어올린다.** `database/`가 아직 없는 것이 그 반대 사례다 — 여러 Repository를 하나의 트랜잭션으로 묶어야 하는 시나리오가 아직 없어서, 미리 빈 추상화를 만들지 않았다(YAGNI).

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — 전체 실제 트리
- [aggregate-id.md](aggregate-id.md) — `common.IdGenerator` 실제 배치
- [secret-manager.md](secret-manager.md) — `common.config.SecretsEnvironmentPostProcessor` 실제 배치
- [domain-events.md](domain-events.md) — `outbox/` 패키지의 실제 구조
- [authentication.md](authentication.md) — `auth/` 패키지의 실제 구조
- [module-pattern.md](module-pattern.md) — Spring이 "공유 모듈" 개념 자체를 요구하지 않는 이유
