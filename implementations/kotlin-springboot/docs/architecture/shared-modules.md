# 공유 코드 배치 — Kotlin Spring Boot

## 현재 상태 — 공유 패키지가 아직 없다

`examples/src/main/kotlin/com/example/accountservice/`를 확인한 결과, 현재 트리는 `account/`와 `notification/` 두 패키지뿐이다.

```
com.example.accountservice/
  AccountServiceApplication.kt
  account/           ← Bounded Context
  notification/      ← Technical Service (Account BC 소속, 별도 BC 아님)
```

`common/`, `config/`, `outbox/`, `auth/` 같은 공유 패키지는 아직 존재하지 않는다 — [directory-structure.md](directory-structure.md)의 "공용 인프라 배치 — 아직 없는 것들" 절이 이미 이 갭을 명시하고 있다. 이 문서는 그 표를 확장해, **도입 시점에 각 공유 코드가 어디에 배치되어야 하는지**를 정리한다.

## 권장 패키지 배치

```
com.example.accountservice/
  AccountServiceApplication.kt

  common/                          ← 프로젝트 공통 유틸 (도메인 아님)
    GlobalExceptionHandler.kt        ← @RestControllerAdvice (error-handling.md)
    CorrelationIdFilter.kt           ← Filter (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt     ← HandlerInterceptor (cross-cutting-concerns.md)
    RateLimitingFilter.kt            ← Filter (rate-limiting.md, 미구현)
    WebConfig.kt                     ← @Configuration, Interceptor/CORS 등록 (bootstrap.md)
    GenerateId.kt                    ← Aggregate ID 생성 유틸 (aggregate-id.md)

  config/                          ← @ConfigurationProperties data class 모음
    DatabaseProperties.kt             (config.md)
    JwtProperties.kt
    SesProperties.kt

  auth/                            ← 인증 공유 모듈 (여러 BC가 함께 사용)
    AuthService.kt                    ← 토큰 발급/검증 (authentication.md)
    JwtAuthenticationFilter.kt         ← Bearer 토큰 추출 Filter
    SecurityConfig.kt                 ← @Configuration, 화이트리스트 경로

  outbox/                          ← Outbox 패턴 (여러 BC가 이벤트 발행에 공용 사용)
    OutboxEntry.kt                    ← @Entity (domain-events.md)
    OutboxWriter.kt
    OutboxRelay.kt                    ← @Scheduled 폴링 (scheduling.md)

  account/                         ← Bounded Context
    domain/ application/ infrastructure/ interfaces/

  notification/                    ← Technical Service (Account BC 소속)
    application/ infrastructure/
```

이 배치는 NestJS 구현(`implementations/nestjs/docs/architecture/shared-modules.md`)이 `src/common/`, `src/database/`, `src/outbox/`, `src/auth/`로 나누는 것과 동일한 발상을 Kotlin 패키지 구조로 옮긴 것이다 — 다만 이 저장소는 아직 단일 BC라 이 패키지들이 실제로 필요해질 시점이 다소 뒤로 미뤄져 있을 뿐, 원칙 자체는 지금 정해 두는 편이 두 번째 BC나 인증 도입 시 혼선을 줄인다.

## 각 패키지의 판단 기준

| 패키지 | 어떤 코드가 여기 속하는가 | 아직 여기 속하지 않는 것 |
|---|---|---|
| `common/` | 어떤 BC의 비즈니스 로직도 포함하지 않는 프레임워크 공통 코드(에러 변환, 필터, ID 생성) | BC별 예외(`AccountException`)는 각 BC의 `domain/`에 남는다 |
| `config/` | `@ConfigurationProperties` 바인딩 전용 `data class`. 이 자체가 로직을 갖지 않는다 | `SesConfig`처럼 `@Bean` 팩토리 메서드가 있는 클래스는 관련 BC/Technical Service의 `infrastructure/`에 남는다(예: `notification/infrastructure/SesConfig.kt`) — `config/`는 설정 *값*의 타입만 모은다 |
| `auth/` | 인증/인가처럼 여러 BC가 공통으로 참조해야 하는 인프라. 특정 BC의 비즈니스 규칙이 아니다 | 인가 규칙 중 BC별 소유권 검사(`account.ownerId == requesterId`)는 각 BC의 Application Service에 남는다 |
| `outbox/` | Domain Event를 트랜잭션 안전하게 외부로 전파하는 기술적 인프라(Outbox 테이블, Relay, Consumer) | 이벤트 자체(`AccountCreatedEvent` 등)는 각 BC의 `domain/`에 남는다 — Outbox는 그 이벤트를 나르는 배관일 뿐 |

**공통 판단 기준**: "이 코드가 여러 BC에서 재사용되는가"와 "이 코드가 특정 BC의 비즈니스 불변식을 담고 있는가"를 함께 본다. 전자만 해당하면 공유 패키지로, 후자에 해당하면(재사용 여부와 무관하게) 해당 BC의 4레이어 안에 남긴다.

## `notification/`은 왜 공유 패키지가 아닌가

`notification/`이 `common/`처럼 보일 수 있지만 — 지금은 Account BC만 이를 사용하므로 **BC 소속 Technical Service**로 분류되어 있다([directory-structure.md](directory-structure.md) 참조). 두 번째 BC가 생기고 그 BC도 이메일 발송이 필요해지는 시점에, `notification/`을 최상위(공유) 패키지로 승격할지 각 BC가 독립적인 알림 로직을 갖게 할지 판단하면 된다 — 지금 미리 공유 패키지로 옮기는 것은 과설계다.

## `@Global`에 대응하는 것 — 없음, 컴포넌트 스캔으로 충분

NestJS는 `DatabaseModule`/`OutboxModule`을 `@Global()`로 선언해 모든 모듈에서 명시적 `imports` 없이 접근하게 한다. Spring/Kotlin에는 이런 개념 자체가 없다 — **컴포넌트 스캔 루트(`com.example.accountservice`) 하위라면 어떤 패키지의 빈이든 위치와 무관하게 주입 가능**하다. `outbox/OutboxWriter`가 `@Component`이기만 하면 `account/application/command/CreateAccountService`가 그대로 생성자 주입받을 수 있다 — NestJS의 "global 모듈" 같은 별도 개념이 필요 없는 것도 [module-pattern.md](module-pattern.md)에서 다룬 "패키지 = 암묵적 경계"의 연장선이다.

## 원칙

- **지금은 `common/`/`config/`/`auth/`/`outbox/`가 없다** — 필요해지는 시점(두 번째 BC, 인증 도입, Outbox 전환)에 위 배치를 기준으로 추가한다.
- **판단 기준은 "재사용 여부"와 "비즈니스 불변식 소유 여부"** — 둘 다 아니면 공유 패키지, 후자면 BC 안에 남긴다.
- **`@Global` 같은 별도 선언이 필요 없다** — 컴포넌트 스캔 루트 하위면 패키지 위치와 무관하게 주입 가능하다.
- **BC 소속 Technical Service(`notification/`)를 성급하게 공유 패키지로 승격하지 않는다** — 실제로 두 번째 소비자가 생겼을 때 판단한다.

### 관련 문서

- [directory-structure.md](directory-structure.md) — 현재 패키지 트리, "아직 없는 것들" 표
- [module-pattern.md](module-pattern.md) — 컴포넌트 스캔이 패키지 경계를 대체하는 방식
- [domain-events.md](domain-events.md) — Outbox 패턴 상세
- [authentication.md](authentication.md) — 인증 공유 모듈 상세
- [config.md](config.md) — `@ConfigurationProperties` data class 설계
