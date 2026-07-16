# 공유 코드 배치 — Kotlin Spring Boot

## 현재 상태 — `common/`/`config/`/`auth/`/`secret/`/`outbox/` 모두 존재

`examples/src/main/kotlin/com/example/accountservice/`를 확인한 결과, 현재 트리는 `account/`, `notification/`(Technical Service)에 더해 **공유 패키지 다섯 개**(`common/`, `config/`, `auth/`, `secret/`, `outbox/`)를 모두 갖추고 있다.

```
com.example.accountservice/
  AccountServiceApplication.kt
  common/            ← 이미 존재 — CorrelationIdFilter/RequestLoggingInterceptor/WebConfig/GenerateId (cross-cutting-concerns.md, aggregate-id.md)
  config/            ← 이미 존재 — AwsProperties/SesProperties (config.md)
  auth/               ← 이미 존재 — AuthService/JwtAuthenticationFilter/SecurityConfig + Credential Aggregate (authentication.md)
  secret/             ← 이미 존재 — SecretService/SecretServiceImpl/SecretsEnvironmentPostProcessor (secret-manager.md)
  outbox/             ← 이미 존재 — OutboxEvent/OutboxWriter/OutboxRelay (domain-events.md)
  account/           ← Bounded Context
  notification/      ← Technical Service (Account BC 소속, 별도 BC 아님)
```

[directory-structure.md](directory-structure.md)의 "공용 인프라 배치" 절이 이 현황을 이미 명시하고 있다. 이 문서는 그 표를 확장해, **각 공유 코드가 왜 그 패키지에 배치되었는지**와 **다음에 무엇이 추가될 수 있는지**를 정리한다.

## 실제 패키지 배치

```
com.example.accountservice/
  AccountServiceApplication.kt

  common/                          ← 프로젝트 공통 유틸 (도메인 아님) — 실제 코드
    CorrelationIdFilter.kt           ← Filter (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt     ← HandlerInterceptor (cross-cutting-concerns.md)
    WebConfig.kt                     ← @Configuration, Interceptor 등록
    GenerateId.kt                    ← Aggregate ID 생성 유틸 (aggregate-id.md)

  config/                          ← @ConfigurationProperties data class 모음 — 실제 코드
    AwsProperties.kt                  ← prefix="aws", @Validated (config.md)
    SesProperties.kt                  ← prefix="ses", @Validated

  auth/                            ← 인증 공유 모듈 (여러 BC가 함께 사용) — 실제 코드
    application/AuthService.kt        ← 토큰 발급 (authentication.md)
    application/command/SignUpService.kt / SignInService.kt   ← 가입/로그인 유스케이스
    application/query/CredentialQuery.kt        ← Credential 읽기 전용 포트
    application/service/PasswordHasher.kt       ← Technical Service interface
    domain/Credential.kt                        ← Aggregate — 유일하게 자체 domain/을 가진 공유 패키지
    domain/CredentialRepository.kt              ← Credential 쓰기 전용 포트
    infrastructure/JwtAuthenticationFilter.kt   ← Bearer 토큰 검증 Filter
    infrastructure/SecurityConfig.kt            ← @Configuration, 화이트리스트 경로
    infrastructure/BCryptPasswordHasher.kt      ← PasswordHasher 구현체
    infrastructure/CredentialRepositoryImpl.kt  ← CredentialRepository + CredentialQuery 구현체
    interfaces/rest/AuthController.kt           ← 가입/로그인 엔드포인트

  secret/                          ← Secrets Manager 연동 — 실제 코드
    application/service/SecretService.kt        ← interface (secret-manager.md)
    infrastructure/SecretServiceImpl.kt          ← TTL 캐시
    infrastructure/SecretManagerConfig.kt        ← @Configuration, SecretsManagerClient Bean
    infrastructure/SecretsEnvironmentPostProcessor.kt ← prod에서 jwt.secret 주입

  outbox/                          ← Outbox 패턴 (여러 BC가 이벤트 발행에 공용 사용) — 실제 코드
    OutboxEvent.kt                    ← @Entity (domain-events.md)
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                   ← Repository.save() 트랜잭션 안에서 이벤트를 Outbox 행으로 적재
    OutboxRelay.kt                    ← Command Service가 저장 직후 동기 호출 — @Scheduled 폴링 아님

  account/                         ← Bounded Context
    domain/ application/ infrastructure/ interfaces/

  notification/                    ← Technical Service (Account BC 소속)
    application/ infrastructure/
```

이 배치는 NestJS 구현(`implementations/nestjs/docs/architecture/shared-modules.md`)이 `src/common/`, `src/database/`, `src/outbox/`, `src/auth/`로 나누는 것과 동일한 발상을 Kotlin 패키지 구조로 옮긴 것이다. 다섯 공유 패키지(`common/`, `config/`, `auth/`, `secret/`, `outbox/`) 모두 실제로 이 배치대로 만들어져 있다 — 인증(`auth/`)과 Secrets Manager 연동(`secret/`)이 필요해지면서 `outbox/`에 이어 추가되었다.

### 아직 실제로 필요해지지 않은 것 — 향후 확장 시 배치 기준만 정해둔 항목

- **`common/GlobalExceptionHandler.kt`**(`@RestControllerAdvice`): 현재 에러 변환은 `AccountController` 내부의 `@ExceptionHandler` 메서드로 처리된다([error-handling.md](error-handling.md) 참조) — 여러 Controller가 생기면 `common/`으로 승격을 고려한다.
- **`common/RateLimitingFilter.kt`**: [rate-limiting.md](rate-limiting.md)가 정의하는 방향성 문서일 뿐 아직 코드가 없다.
- **`config/`의 DB 전용 `data class`**: 이 저장소는 `DatabaseProperties` 같은 별도 클래스를 두지 않는다 — DB 연결 정보는 Spring Boot 표준 `spring.datasource.*` relaxed binding으로 충분하다고 판단했다([config.md](config.md) 참조).

## 각 패키지의 판단 기준

| 패키지 | 어떤 코드가 여기 속하는가 | 아직 여기 속하지 않는 것 |
|---|---|---|
| `common/` | 어떤 BC의 비즈니스 로직도 포함하지 않는 프레임워크 공통 코드(에러 변환, 필터, ID 생성) | BC별 예외(`AccountException`)는 각 BC의 `domain/`에 남는다 |
| `config/` | `@ConfigurationProperties` 바인딩 전용 `data class`. 이 자체가 로직을 갖지 않는다 | `SesConfig`처럼 `@Bean` 팩토리 메서드가 있는 클래스는 관련 BC/Technical Service의 `infrastructure/`에 남는다(예: `notification/infrastructure/SesConfig.kt`) — `config/`는 설정 *값*의 타입만 모은다 |
| `auth/` | 인증/인가처럼 여러 BC가 공통으로 참조해야 하는 인프라. 특정 BC의 비즈니스 규칙이 아니다 | 인가 규칙 중 BC별 소유권 검사(`account.ownerId == requesterId`)는 각 BC의 Application Service에 남는다 |
| `outbox/` | Domain Event를 트랜잭션 안전하게 외부로 전파하는 기술적 인프라(Outbox 테이블, Relay, Consumer) | 이벤트 자체(`AccountCreatedEvent` 등)는 각 BC의 `domain/`에 남는다 — Outbox는 그 이벤트를 나르는 배관일 뿐 |
| `secret/` | Secrets Manager 조회/캐싱처럼 여러 BC가 공통으로 참조할 수 있는 기술 인프라 | 어떤 시크릿을 언제 조회할지에 대한 BC별 판단(예: 이벤트 핸들러가 특정 시크릿을 쓸지)은 각 BC의 Application 레이어에 남는다 |

**공통 판단 기준**: "이 코드가 여러 BC에서 재사용되는가"와 "이 코드가 특정 BC의 비즈니스 불변식을 담고 있는가"를 함께 본다. 전자만 해당하면 공유 패키지로, 후자에 해당하면(재사용 여부와 무관하게) 해당 BC의 4레이어 안에 남긴다.

## `notification/`은 왜 공유 패키지가 아닌가

`notification/`이 `common/`처럼 보일 수 있지만 — 지금은 Account BC만 이를 사용하므로 **BC 소속 Technical Service**로 분류되어 있다([directory-structure.md](directory-structure.md) 참조). 두 번째 BC가 생기고 그 BC도 이메일 발송이 필요해지는 시점에, `notification/`을 최상위(공유) 패키지로 승격할지 각 BC가 독립적인 알림 로직을 갖게 할지 판단하면 된다 — 지금 미리 공유 패키지로 옮기는 것은 과설계다.

## `@Global`에 대응하는 것 — 없음, 컴포넌트 스캔으로 충분

NestJS는 `DatabaseModule`/`OutboxModule`을 `@Global()`로 선언해 모든 모듈에서 명시적 `imports` 없이 접근하게 한다. Spring/Kotlin에는 이런 개념 자체가 없다 — **컴포넌트 스캔 루트(`com.example.accountservice`) 하위라면 어떤 패키지의 빈이든 위치와 무관하게 주입 가능**하다. `outbox/OutboxWriter`가 `@Component`이기만 하면 `account/application/command/CreateAccountService`가 그대로 생성자 주입받을 수 있다 — NestJS의 "global 모듈" 같은 별도 개념이 필요 없는 것도 [module-pattern.md](module-pattern.md)에서 다룬 "패키지 = 암묵적 경계"의 연장선이다.

## 원칙

- **`common/`/`config/`/`auth/`/`secret/`/`outbox/` 다섯 공유 패키지 모두 이미 있다** — 인증, Secrets Manager 연동, Outbox가 실제로 필요해지면서 차례로 추가되었다. `GlobalExceptionHandler`/`RateLimitingFilter`처럼 아직 필요해지지 않은 항목만 배치 기준을 미리 정해둔 상태다.
- **판단 기준은 "재사용 여부"와 "비즈니스 불변식 소유 여부"** — 둘 다 아니면 공유 패키지, 후자면 BC 안에 남긴다.
- **`@Global` 같은 별도 선언이 필요 없다** — 컴포넌트 스캔 루트 하위면 패키지 위치와 무관하게 주입 가능하다.
- **BC 소속 Technical Service(`notification/`)를 성급하게 공유 패키지로 승격하지 않는다** — 실제로 두 번째 소비자가 생겼을 때 판단한다.

### 관련 문서

- [directory-structure.md](directory-structure.md) — 현재 패키지 트리, "공용 인프라 배치" 표
- [module-pattern.md](module-pattern.md) — 컴포넌트 스캔이 패키지 경계를 대체하는 방식
- [domain-events.md](domain-events.md) — Outbox 패턴 상세
- [authentication.md](authentication.md) — 인증 공유 모듈 상세
- [secret-manager.md](secret-manager.md) — Secrets Manager 연동 상세
- [config.md](config.md) — `@ConfigurationProperties` data class 설계
