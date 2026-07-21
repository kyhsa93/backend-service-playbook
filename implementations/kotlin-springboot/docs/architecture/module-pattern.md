# Spring DI 컨테이너 — Kotlin Spring Boot

NestJS는 `@Module({ providers, controllers, exports, imports })`로 의존 그래프를 **명시적으로** 선언한다. Spring은 이런 모듈 선언 파일이 없다 — **컴포넌트 스캔(classpath scanning) + 스테레오타입 애노테이션**이 같은 역할을 암묵적으로 수행한다. 이 문서는 NestJS의 `module-pattern.md`가 다루는 것과 같은 주제(DI 컨테이너 메커니즘, 순환 의존 회피, 패키지 조직)를 Spring의 실제 방식으로 다룬다.

## 스테레오타입 애노테이션 — 레이어별 역할

| 애노테이션 | 배치 레이어 | 역할 |
|---|---|---|
| `@Service` | `application/{command,query}/` | 유스케이스 1개 = 클래스 1개 |
| `@Repository` | `infrastructure/persistence/` | Repository 인터페이스 구현체 |
| `@Component` | `application/event/`, `infrastructure/` | 그 외 일반 빈 (Adapter 구현체, Listener 등) |
| `@Configuration` | `infrastructure/`, `config/` | `@Bean` 팩토리 메서드를 담는 클래스 |
| `@RestController` | `interfaces/rest/` | HTTP 엔드포인트 |

harness의 `service-annotation`(`@Service`는 `application/` 안에만), `repository-annotation`(`@Repository`는 `infrastructure/` 안에만) 검사가 이 배치를 실제로 강제한다 — NestJS의 `providers` 배열 등록이 하는 일을, 여기서는 "올바른 패키지에 올바른 애노테이션을 붙였는가"라는 정적 검사로 대체한 셈이다.

이 네 가지는 모두 `@Component`의 메타 애노테이션(내부적으로 `@Component`를 합성)이다 — Spring이 빈으로 등록하는 기준은 결국 "`@Component` 계열 애노테이션이 붙어 있고 컴포넌트 스캔 대상 패키지 안에 있는가" 하나뿐이다. `@Service`/`@Repository`로 세분화하는 것은 런타임 동작 차이(일부 예외 변환 등)와 코드 가독성을 위한 것이지, DI 등록 여부 자체를 바꾸지 않는다.

## 생성자 주입 — Kotlin 주 생성자가 곧 DI 선언

```kotlin
// application/command/CreateAccountService.kt — 실제 코드
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult { /* ... */ }
}
```

Java Spring이라면 `private final AccountRepository accountRepository;` 필드 선언 + 생성자 본문의 대입문 + (생성자가 여러 개면) `@Autowired`까지 세 곳에 나눠 썼을 코드가, Kotlin은 **주 생성자 파라미터 선언 한 줄**로 끝난다. `private val`이 필드 선언과 대입을 동시에 수행하고, 생성자가 하나뿐이면 Spring 4.3+가 `@Autowired` 없이 자동으로 이를 DI 생성자로 인식한다.

**`open` 필요성**: Kotlin 클래스는 기본적으로 `final`이라 상속이 불가능한데, Spring AOP(`@Transactional`의 트랜잭션 프록시 등)는 CGLIB 클래스 상속 기반 프록시를 생성해야 한다. `kotlin("plugin.spring")` 컴파일러 플러그인이 `@Component`/`@Service`/`@Repository`/`@Configuration`이 붙은 클래스를 자동으로 `open` 처리하므로, 소스에 `open` 키워드를 직접 쓸 필요가 없다 — `build.gradle.kts`에 이 플러그인이 적용되어 있다.

## `@Bean` — `@Configuration` 클래스의 팩토리 메서드

생성자 주입만으로 만들 수 없는 빈(서드파티 SDK 클라이언트 등)은 `@Configuration` 클래스의 `@Bean` 함수로 등록한다.

```kotlin
// notification/infrastructure/SesConfig.kt — 실제 코드
@Configuration
class SesConfig {

    @Bean
    fun sesClient(
        @Value("\${AWS_REGION:us-east-1}") region: String,
        @Value("\${AWS_ACCESS_KEY_ID:test}") accessKeyId: String,
        @Value("\${AWS_SECRET_ACCESS_KEY:test}") secretAccessKey: String,
        @Value("\${AWS_ENDPOINT_URL:}") endpointUrl: String,
    ): SesClient {
        val builder = SesClient.builder()
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
            )
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        return builder.build()
    }
}
```

`SesClient`는 `NotificationServiceImpl`(`@Component`)의 생성자 파라미터로 자동 주입된다 — `SesConfig`가 어디에 있든, 어떤 클래스가 `SesClient`를 필요로 하든 상관없이 Spring이 타입 기준으로 연결한다. [config.md](config.md)가 제안하는 개선안(`@Value` 대신 `SesProperties` data class 주입)으로 바꿔도 `@Bean` 메서드의 역할 자체는 동일하다.

**함수형 스타일**: Kotlin의 `@Bean` 메서드는 `fun` 키워드로 선언되는 일반 함수다 — Java의 메서드 오버로딩 규칙과 달리, 함수 하나가 반환 타입으로 빈 타입을 결정하고 Spring이 이를 리플렉션으로 읽는다는 점은 Java와 동일하다. Kotlin다운 차이는 문법(`fun sesClient(): SesClient = ...`처럼 단일 표현식 함수로 축약 가능)에 그친다.

## 패키지 = Bounded Context 경계

```
com.example.accountservice/
  account/           ← Account Bounded Context — 4레이어 전부 포함
    domain/
    application/
    infrastructure/
    interfaces/
  notification/      ← Technical Service (Bounded Context 아닌 최상위 공유 패키지, shared-modules.md 참조)
    application/
    infrastructure/
  AccountServiceApplication.kt
```

NestJS는 `@Module`로 "이 패키지가 하나의 단위"임을 명시적으로 선언해야 하지만, Kotlin/Spring은 **패키지 자체가 이미 그 경계**다 — 별도의 `AccountModule` 클래스나 등록 파일이 없다. 컴포넌트 스캔의 루트(`@SpringBootApplication`이 붙은 `AccountServiceApplication.kt`의 패키지, `com.example.accountservice`)만 지정되면, 그 하위 모든 패키지가 스캔 대상이 된다.

이것이 NestJS 대비 갖는 트레이드오프이기도 하다 — `@Module`의 `exports` 배열처럼 "이 BC가 외부에 공개하는 것"을 코드로 강제할 방법이 없다. 어떤 BC의 Service를 다른 BC의 Application 레이어에서 직접 import하는 것을 막는 장치는 없다(테스트/코드 리뷰/harness 정적 검사로 대신한다). Adapter를 통한 간접 호출 규율([cross-domain.md](cross-domain.md))은 이 부재를 팀 컨벤션으로 메우는 것이다.

## 순환 의존 회피 — Spring은 기동 시점에 실패한다

NestJS는 순환 의존(A → B → A)을 `forwardRef()`로 우회할 수 있다. **Spring의 생성자 주입은 순환 의존을 우회할 방법이 마땅치 않다** — 생성자 주입 방식에서 순환 의존이 있으면 `BeanCurrentlyInCreationException`으로 **애플리케이션 기동 자체가 실패**한다.

```
com.example.accountservice.account.application.command.CreateAccountService
  → 요구: com.example.accountservice.user.application.service.UserService
    → 요구: com.example.accountservice.account.application.query.GetAccountService  (순환!)

***************************
APPLICATION FAILED TO START
***************************

Description:
The dependencies of some of the beans in the application context form a cycle:
...
```

`@Lazy` 애노테이션으로 프록시를 주입해 우회하는 방법이 기술적으로는 존재하지만, 이는 근본 원인(BC 경계가 잘못 설정되었거나 두 서비스가 실제로는 하나여야 함)을 감추는 임시방편이다. **권장 대응은 우회가 아니라 재설계**다.

1. **Adapter 패턴으로 방향을 단방향화** — 한쪽만 상대를 Adapter로 호출하고, 반대 방향은 Integration Event(비동기)로 전환한다 ([cross-domain.md](cross-domain.md), [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)).
2. **공통으로 필요한 로직을 제3의 위치로 추출** — 양쪽 BC가 의존하는 로직이 있다면 Technical Service나 공유 유틸로 뽑아낸다 ([shared-modules.md](shared-modules.md)).
3. **BC 경계 자체를 재검토** — 순환 의존은 대개 두 "BC"가 실제로는 하나의 응집된 도메인이라는 신호다.

**Spring이 기동 시점에 강제로 실패시킨다는 것 자체가 장점이다** — NestJS의 `forwardRef()`는 순환을 "동작하게" 만들어 문제를 코드 리뷰 단계로 미루지만, Spring은 배포 전(로컬 실행, CI) 단계에서 즉시 드러낸다.

## Controller 구성

```kotlin
// interfaces/rest/AccountController.kt — 실제 코드 (일부)
@RestController
@RequestMapping("/accounts")
class AccountController(
    private val createAccountService: CreateAccountService,
    private val depositService: DepositService,
    // ...
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestHeader("X-User-Id") requesterId: String,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult =
        createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
}
```

`AccountController`가 8개의 Command/Query Service를 생성자로 직접 주입받는다 — NestJS처럼 Controller를 모듈의 `controllers` 배열에 등록하는 절차가 없다(`@RestController` 자체가 이미 `@Component`를 포함한 스테레오타입이라 컴포넌트 스캔으로 충분하다).

## 정리 — NestJS 대비 무엇이 다른가

| 관심사 | NestJS | Kotlin/Spring Boot |
|---|---|---|
| 빈 등록 단위 선언 | `@Module({ providers: [...] })` 명시적 배열 | 패키지 + 스테레오타입 애노테이션 (암묵적) |
| DI 토큰 | `abstract class` 또는 문자열 토큰 | `interface` 자체가 토큰 |
| 외부 공개 제어 | `exports` 배열로 명시 | 언어/프레임워크 차원 강제 없음 (컨벤션 + harness 검사) |
| 순환 의존 | `forwardRef()`로 우회 가능 | 기동 시점 예외로 실패 — 재설계 강제 |
| 조건부 빈 | `imports` 배열의 삼항 연산자 | `@Profile`, `@ConditionalOnProperty` |

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향과 DI 토큰
- [cross-domain.md](cross-domain.md) — Adapter 구현체 등록
- [shared-modules.md](shared-modules.md) — 공유 코드의 패키지 배치
- [directory-structure.md](directory-structure.md) — 패키지 트리 전체
