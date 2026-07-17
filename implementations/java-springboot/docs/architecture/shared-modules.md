# 공유 코드 구조 (Spring Boot)

> NestJS 대비 문서. NestJS의 `src/common/`/`src/database/`/`src/outbox/`/`src/auth/`(각각 별도 `@Module`)에 대응하되, Spring Boot는 "공유 모듈"이라는 별도 컨테이너 단위가 없다 — [module-pattern.md](module-pattern.md)에서 설명하듯 패키지 자체가 곧 관례상 경계다.

## 현재 실제 상태 — `outbox/`는 실제로 존재, 나머지는 아직 없다

`examples/src/main/java/com/example/accountservice/` 트리 전체를 확인한 결과, 최상위 패키지는 `account/`와 **`outbox/`** 둘뿐이다. `outbox/`(`OutboxEvent`/`OutboxWriter`/`OutboxRelay`/`OutboxEventHandler`)는 알림 발송이 유실되면 안 되는 부가효과여서 실제로 필요해져 존재한다([domain-events.md](domain-events.md) 참고) — 여러 도메인이 실제로 공유하는 진짜 공용 인프라다. `notification`(Technical Service)은 `account`만 사용하므로 최상위가 아니라 `account/` 내부(`account/application/service/`, `account/infrastructure/notification/`)에 있다 — 공유되지 않는 코드를 미리 공용 패키지로 끌어올리지 않는다는 원칙의 실제 예다. `common/`, `database/`, `auth/` 같은 나머지 도메인 무관 공유 패키지는 아직 없다 — `IdGenerator`(아직 `UUID.randomUUID().toString()`을 도메인 코드에 직접 인라인), 트랜잭션 관리, 인증 공유 로직은 이 저장소에는 아직 코드로 존재하지 않는다.

이는 [directory-structure.md](directory-structure.md) "공용 인프라 배치 기준 — 아직 부재" 섹션이 이미 지적한 gap과 정확히 같은 지점이다 — 이 문서는 그 배치 기준을 공유 코드 관점에서 조금 더 구체화한다.

---

## 권장 배치 — 새로 추가할 때의 컨벤션

도메인(`account`)에 속하지 않는 코드는 `com.example.accountservice`(최상위) 바로 아래, 도메인 패키지와 같은 레벨에 관심사별로 둔다.

```
com.example.accountservice/
  common/                  # 프레임워크 무의존 순수 유틸 + Interface 레이어 공용 부품
    IdGenerator.java        # aggregate-id.md 참고 — 순수 Java, 어떤 프레임워크도 import 안 함
    web/
      GlobalExceptionHandler.java   # @RestControllerAdvice, error-handling.md 참고
      CorrelationIdFilter.java      # cross-cutting-concerns.md 참고
      RequestLoggingInterceptor.java
    config/
      SecretsEnvironmentPostProcessor.java   # secret-manager.md 참고
      OpenApiConfig.java                      # bootstrap.md 참고
      WebConfig.java                          # CORS, Interceptor 등록, bootstrap.md 참고

  config/                  # @ConfigurationProperties record 전용 (common/config와 역할 구분)
    AwsProperties.java       # config.md 참고
    SesProperties.java

  database/                 # (도메인이 늘어나 공유 DataSource/트랜잭션 유틸이 필요해지면)
    TransactionTemplateConfig.java

  outbox/                   # domain-events.md 참고
    OutboxEvent.java          # @Entity — Outbox 테이블 매핑
    OutboxEventJpaRepository.java
    OutboxEventHandler.java   # 이벤트 타입별 Handler가 구현하는 인터페이스
    OutboxWriter.java         # Repository.save() 트랜잭션 안에서 이벤트를 Outbox 행으로 적재
    OutboxRelay.java          # Command Service가 저장 직후 동기 호출 — @Scheduled 폴링 아님

  auth/                     # authentication.md의 JWT 인증 도입 시
    SecurityConfig.java       # @Configuration, SecurityFilterChain
    JwtTokenProvider.java

  account/                  # 도메인 패키지 (기존과 동일)
    domain/ application/ infrastructure/ interfaces/
    application/service/NotificationService.java        # 도메인 스코프 Technical Service
    infrastructure/notification/                          # 구현체 — account만 사용, 공유 패키지 아님

  AccountServiceApplication.java
```

**`common/`과 `config/`를 나눈 이유**: `common/`은 도메인 무관 **범용 유틸/Interface 레이어 부품**(ID 생성, 전역 예외 처리, 필터)이고, `config/`는 **`@ConfigurationProperties` 바인딩 전용 record**([config.md](config.md) 참고)다. 후자는 Infrastructure 레이어에서만 주입받는다는 제약이 있어 역할이 명확히 다르므로 분리한다 — 다만 프로젝트 규모가 작다면 `common/config/`로 합쳐도 무방하다. 이미 [secret-manager.md](secret-manager.md)가 `com.example.accountservice.common.config.SecretsEnvironmentPostProcessor`를, [aggregate-id.md](aggregate-id.md)가 `com.example.accountservice.common.IdGenerator`를 각각 이 배치대로 참조하고 있다 — 이 문서가 그 배치를 하나의 트리로 통합해 보여준다.

---

## NestJS 공유 모듈과의 대응

| NestJS (`@Module`, `@Global`) | Spring Boot 대응 | 이 저장소의 상태 |
|---|---|---|
| `src/common/` (필터, 인터셉터, 유틸) | `common/` 패키지 — `@Component`/순수 유틸 혼재 | 없음 (신설 필요) |
| `src/database/` (`@Global` — DataSource, TransactionManager) | `database/` 패키지 — 단, Spring은 `@Global` 개념 자체가 불필요 | 없음 (JPA `DataSource`는 auto-configuration이 이미 전역 제공) |
| `src/outbox/` (`@Global` — OutboxWriter/Relay/Consumer) | `outbox/` 패키지 | 있음 — `OutboxWriter`/`OutboxRelay`/`OutboxEventHandler`(domain-events.md 참고) |
| `src/auth/` (인증 공유 모듈) | `auth/` 패키지 | 없음 (authentication.md의 제안 상태) |

**`@Global` 데코레이터가 Spring에는 없는 이유**: NestJS는 기본적으로 모듈 스코프가 닫혀 있어 `@Global`로 명시해야 모든 모듈에서 `imports` 없이 주입 가능해진다. Spring Boot는 애초에 전역 스코프가 기본값이다([module-pattern.md](module-pattern.md) "근본적 차이" 참고) — `database/`에 `DataSource` `@Bean`을 두면 별도 표시 없이 이미 어디서든 주입 가능하다. 즉 Spring에서는 "공유 모듈"이 NestJS처럼 특별한 선언을 요구하는 개념이 아니라, **그냥 패키지를 나누는 정리 방식**일 뿐이다.

---

## 공유 코드와 도메인 코드를 나누는 기준

- **두 개 이상의 도메인/Technical Service가 참조하면 공유 코드다.** `IdGenerator`는 `account`(및 향후 다른 Aggregate)가 모두 쓸 유틸이므로 `common/`에 둔다. 반대로 `NotificationServiceImpl`은 `account` 도메인 하나만 쓰므로 최상위 공유 패키지가 아니라 `account/infrastructure/notification/`에 남는다.
- **공유 코드도 레이어 규율을 그대로 따른다.** `common/IdGenerator`는 어떤 프레임워크도 import하지 않는 순수 유틸이라 Domain 레이어(`Account.create()`)에서 직접 호출할 수 있다. 반면 `common/web/GlobalExceptionHandler`는 Spring MVC 타입(`ResponseEntity`, `@RestControllerAdvice`)에 의존하므로 Interface 레이어 성격의 공유 코드이고, Domain/Application에서 참조해서는 안 된다.
- **도메인이 하나뿐인 지금 단계에서 공유 패키지를 미리 만들 필요는 없다.** `IdGenerator`조차 아직 `account/domain/Account.java`에 인라인되어 있다([aggregate-id.md](aggregate-id.md) 참고) — 두 번째 도메인이 추가되어 실제로 코드가 중복되는 시점에 `common/`으로 추출하는 것이 YAGNI 원칙에 맞다. 위 트리는 "그 시점이 오면 어디에 두는가"에 대한 답이지, 지금 당장 빈 패키지를 만들라는 지시가 아니다.

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — "공용 인프라 배치 기준 — 아직 부재" (이 문서의 근거가 된 원출처)
- [aggregate-id.md](aggregate-id.md) — `common.IdGenerator` 실제 배치 예
- [secret-manager.md](secret-manager.md) — `common.config.SecretsEnvironmentPostProcessor` 실제 배치 예
- [domain-events.md](domain-events.md) — `outbox/` 패키지의 실제 구조
- [authentication.md](authentication.md) — `auth/` 패키지 도입 시 구조
- [module-pattern.md](module-pattern.md) — Spring이 "공유 모듈" 개념 자체를 요구하지 않는 이유
