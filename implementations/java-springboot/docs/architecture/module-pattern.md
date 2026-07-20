# Spring DI 컨테이너 패턴

> NestJS 대비 문서. NestJS의 `@Module`(명시적 `providers`/`controllers`/`imports`/`exports` 선언)에 대응하는 개념이지만, Spring의 DI 컨테이너는 **모듈 경계를 언어/프레임워크 차원에서 강제하지 않는다** — 이 문서는 그 차이와, 이 저장소가 실제로 패키지를 어떻게 조직해 같은 효과를 얻는지를 다룬다.

## NestJS `@Module`과의 근본적 차이

NestJS는 `@Module({ providers, controllers, imports, exports })`로 **어떤 빈이 어느 모듈에 속하고 어떤 모듈에 노출되는지를 명시적으로 선언**해야 한다 — 선언하지 않으면 다른 모듈에서 주입 자체가 실패한다.

Spring Boot는 이런 선언이 없다. `@ComponentScan`(사실상 `@SpringBootApplication`이 자동 수행)이 스캔 범위 내 모든 `@Component`/`@Service`/`@Repository`/`@Configuration`/`@RestController`를 **단일 `ApplicationContext`**에 등록하고, 타입이 일치하면 어느 패키지의 클래스든 자동으로 주입 가능하다. 즉:

- **NestJS**: 모듈 경계가 코드로 강제된다 (`imports`에 없는 모듈의 `exports`되지 않은 Provider는 주입 자체가 안 된다).
- **Spring Boot**: 모듈 경계는 **패키지 구조를 통한 관례(convention)**일 뿐, 컴파일러/컨테이너가 강제하지 않는다. 이 저장소가 두 번째 BC를 추가하더라도, 그 패키지의 클래스가 `account` 패키지의 `@Component`를 얼마든지 직접 주입받을 수 있다 — 막는 것은 코드 리뷰와 아키텍처 규율뿐이다.

이 차이는 장단이 있다. NestJS는 실수로 다른 도메인 내부를 직접 참조하면 컴파일이 깨진다(모듈 경계 위반이 빌드 타임에 드러난다). Spring Boot는 같은 실수가 조용히 컴파일되고 런타임에도 정상 동작하므로, [cross-domain.md](cross-domain.md)의 Adapter 패턴 같은 관례를 **강제할 방법이 코드 리뷰 외에 없다** — 이 저장소의 `harness.sh`도 이 부분(도메인 간 직접 참조 금지)은 검사하지 않는다.

---

## 스테레오타입 애노테이션 — Bean 등록 + 레이어 표시를 겸함

Spring은 `@Component`의 특수화(specialization)로 레이어를 구분한다. 아래는 이 저장소가 실제로 사용하는 매핑이다.

| 애노테이션 | 등록 대상 | 이 저장소의 실제 사용처 |
|---|---|---|
| `@Service` | Application 레이어의 유스케이스 조율 서비스 | `CreateAccountService`, `GetAccountService` 등 |
| `@Repository` | Infrastructure 레이어의 Repository 구현체 | `AccountRepositoryImpl` |
| `@Component` | 그 외 일반 빈 (Outbox 이벤트 핸들러, Technical Service 구현체) | `AccountCreatedEventHandler`, `OutboxPoller`, `OutboxConsumer`, `NotificationServiceImpl` |
| `@Configuration` | `@Bean` 팩토리 메서드를 담는 설정 클래스 | `SesConfig` |
| `@RestController` | HTTP 진입점 | `AccountController` |

기능적으로는 넷 다 `@ComponentScan`에 의해 빈으로 등록된다는 점에서 동일하다 — `@Service`/`@Repository`가 `@Component`와 별도로 존재하는 이유는 (1) 레이어 의도를 코드에서 즉시 드러내고, (2) `@Repository`는 추가로 JPA/JDBC 예외를 Spring의 `DataAccessException` 계층으로 변환하는 AOP를 적용하기 때문이다. 이 저장소는 스테레오타입을 실제 레이어와 일치시켜 사용한다 — `@Service`를 Repository 구현체에 붙이거나 그 반대로 쓰지 않는다.

---

## 생성자 주입 — `@Autowired` 필드 주입은 쓰지 않는다

이 저장소 전체에서 필드 주입(`@Autowired private final X x;`)은 한 건도 없다 — 모든 클래스가 Lombok `@RequiredArgsConstructor`로 `final` 필드 생성자 주입을 사용한다.

```java
// account/application/event/AccountSuspendedEventHandler.java — 실제 코드
@Component
@RequiredArgsConstructor
public class AccountSuspendedEventHandler implements OutboxEventHandler {
    private final NotificationService notificationService;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    // Lombok이 생성자를 자동 생성 — @Autowired 불필요
    // (Spring 4.3+는 생성자가 하나뿐인 클래스에 @Autowired 생략을 허용한다)
}
```

생성자 주입을 쓰는 이유:
- **`final` 필드 강제** — 의존성이 생성 이후 재할당될 수 없다. 필드 주입은 필드를 `final`로 선언할 수 없다.
- **불변 의존성 = 순환 의존을 컴파일이 아니라 기동 시점에 즉시 드러낸다** — 아래 참고.
- **테스트에서 mock 주입이 단순하다** — `new AccountSuspendedEventHandler(mockNotificationService, mockOutboxWriter, mockObjectMapper)`로 Spring 컨테이너 없이 직접 생성 가능([testing.md](testing.md) 참고).

---

## `@Bean` 메서드 — 서드파티 타입을 빈으로 등록

Spring이 직접 소유하지 않는 클래스(AWS SDK 클라이언트 등)는 `@Component`를 붙일 수 없다 — 대신 `@Configuration` 클래스 안에 `@Bean` 팩토리 메서드를 둔다.

```java
// account/infrastructure/notification/SesConfig.java — 실제 코드
@Configuration
public class SesConfig {

    @Bean
    public SesClient sesClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl,
            @Value("${aws.access-key-id:test}") String accessKeyId,
            @Value("${aws.secret-access-key:test}") String secretAccessKey
    ) {
        SesClientBuilder builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
```

- `@Bean` 메서드의 **반환 타입**(`SesClient`)이 다른 곳의 주입 지점 타입과 매칭되어 자동 주입된다 — `NotificationServiceImpl` 생성자가 `SesClient`를 받으면 이 메서드가 반환한 인스턴스가 주입된다.
- `@Value`로 메서드 파라미터에 직접 설정값을 받는 것은 이 저장소의 현재 방식이다. [config.md](config.md)가 제안하는 `@ConfigurationProperties` record(`AwsProperties`)로 교체하면 `@Bean` 메서드가 `AwsProperties` 하나만 받도록 단순화된다 — `@Bean` 메서드 자체의 구조는 동일하게 유지된다.
- `SesConfig`는 `account/infrastructure/notification/`(도메인 소속)에 있다. 여러 도메인이 공유하는 `@Configuration`(예: 공용 `ObjectMapper` 빈)이라면 [shared-modules.md](shared-modules.md)가 다루는 공용 패키지에 두는 것이 맞다 — 이 저장소는 아직 그런 공유 설정이 없다.

---

## 패키지 = Bounded Context 경계 (관례)

NestJS의 `1 BC = 1 Module`에 대응하는 Spring Boot의 관례는 `1 BC = 1 최상위 패키지`다.

```
com.example.accountservice/
  account/           ← Account BC — domain/application/infrastructure/interfaces 4레이어 전부 포함
    application/service/NotificationService.java        ← 도메인 스코프 Technical Service 인터페이스
    infrastructure/notification/NotificationServiceImpl.java  ← 구현체 (별도 BC 아님, directory-structure.md 참고)
  AccountServiceApplication.java
```

- **레이어가 아니라 도메인이 최상위 패키지 분리 기준**이다 — `com.example.accountservice.controllers`, `com.example.accountservice.services` 식으로 기술 레이어 기준 패키지를 만들지 않는다.
- 도메인이 늘어나면(예: `payment/`) 같은 레벨에 새 최상위 패키지를 추가하고, 그 안에 다시 4레이어를 둔다.
- **NestJS와 달리 이 구조를 강제하는 컴파일 타임 장치가 없다** — 위 "근본적 차이" 절 참고. `harness.sh`의 `package-structure` 검사가 디렉토리 존재 여부만 확인하고, 도메인 간 직접 참조 자체는 잡지 못한다.

---

## 순환 의존 — NestJS `forwardRef()` vs Spring `@Lazy`

NestJS는 두 모듈이 서로를 `imports`하면 순환이 발생해 `forwardRef(() => OtherModule)`로 우회해야 한다. Spring의 생성자 주입은 이 문제를 **다른 방식으로** 다룬다.

- **생성자 주입 순환은 기동 시점에 즉시 실패한다.** `A`가 생성자로 `B`를 받고 `B`가 생성자로 `A`를 받으면, `ApplicationContext` 초기화 중 `BeanCurrentlyInCreationException`이 발생하며 애플리케이션이 기동조차 되지 않는다 — NestJS가 `forwardRef()` 없이 순환을 방치하면 런타임 에러로 늦게 드러나는 것과 달리, Spring은 **개발자가 즉시 알아채도록 강제한다.**
- Spring도 `@Lazy` 탈출구를 제공한다 — 주입 지점에 `@Lazy`를 붙이면 실제 빈 대신 프록시가 먼저 주입되고, 최초 메서드 호출 시점에 실제 빈을 지연 초기화해 순환을 우회할 수 있다.

```java
// 순환을 @Lazy로 "우회"하는 예 — 권장하지 않음
@Service
public class ServiceA {
    public ServiceA(@Lazy ServiceB serviceB) { this.serviceB = serviceB; }
}
```

**이 저장소는 `@Lazy`를 순환 의존 우회 목적으로 쓰지 않는다.** NestJS `module-pattern.md`가 명시하듯, 순환 의존은 대부분 **Bounded Context 경계를 잘못 그었다는 설계 신호**다 — `@Lazy`(Spring)든 `forwardRef()`(NestJS)든 이 신호를 기술적으로 틀어막을 뿐 근본 원인(BC 경계 재설정, [cross-domain.md](cross-domain.md)의 Adapter로 방향 정리, 또는 이벤트 기반 통신으로 전환)을 해결하지 않는다. 이 저장소는 단일 BC 구조라 아직 순환 의존이 발생한 적이 없다 — 두 번째 BC를 추가할 때 이 원칙을 적용한다.

---

## 조건부 빈 등록 — `@Profile`

NestJS의 `...(process.env.NODE_ENV === 'prd' ? [] : [DevToolModule])` 조건부 모듈 로딩에 대응하는 Spring 메커니즘은 `@Profile`이다.

```java
@Configuration
@Profile("!prod")   // prod 프로필이 아닐 때만 활성화
public class DevToolConfig {
    @Bean
    public DevDataSeeder devDataSeeder() { return new DevDataSeeder(); }
}
```

`spring.profiles.active`([config.md](config.md) 참고)가 결정한 활성 프로필에 따라 `@ComponentScan`이 찾은 빈 중 `@Profile` 조건에 맞지 않는 것은 등록 자체를 건너뛴다 — NestJS의 배열 스프레드 조건문과 달리, `@Configuration` 클래스 선언 시점에 조건이 고정된다.

---

## 요약

| NestJS 개념 | Spring Boot 대응 | 강제 수준 |
|---|---|---|
| `@Module({ providers, controllers })` | `@ComponentScan` (자동, 선언 불필요) | 컨테이너가 자동 등록, 경계는 관례 |
| `imports: [OtherModule]` | 없음 — 같은 `ApplicationContext`면 자동 주입 가능 | 강제 없음 (코드 리뷰 의존) |
| `exports: [SomeService]` | 없음 — `public`이면 사실상 전역 노출 | 강제 없음 |
| `{ provide: Abstract, useClass: Impl }` | 인터페이스 타입 주입 지점에 유일한 구현체 자동 바인딩 | 컨테이너가 자동 해결 |
| `forwardRef(() => B)` | `@Lazy` (권장하지 않음 — BC 경계 재설계가 우선) | 개발자 선택 |
| 조건부 모듈 로딩 | `@Profile("...")` | 컨테이너가 자동 적용 |

---

### 관련 문서

- [cross-domain.md](cross-domain.md) — 도메인 간 호출 시 Adapter 패턴 구현
- [layer-architecture.md](layer-architecture.md) — 레이어별 의존 방향과 스테레오타입
- [config.md](config.md) — `@ConfigurationProperties`, 프로필별 설정
- [shared-modules.md](shared-modules.md) — 여러 도메인이 공유하는 `@Configuration`/`@Component` 배치
- [testing.md](testing.md) — 생성자 주입 덕분에 가능한 Mockito 기반 단위 테스트
