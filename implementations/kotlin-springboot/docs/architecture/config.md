# 환경 설정 관리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root config.md](../../../../docs/architecture/config.md) 참조.

## 적용 완료 — `@ConfigurationProperties` + `data class` (일부 남은 갭 포함)

현재 `examples/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration

jwt:
  secret: ${JWT_SECRET:dev-secret-dev-secret-dev-secret}

aws:
  region: ${AWS_REGION:us-east-1}
  endpoint-url: ${AWS_ENDPOINT_URL:}
  access-key-id: ${AWS_ACCESS_KEY_ID:test}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY:test}

ses:
  sender-email: ${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}
```

(`ddl-auto`/마이그레이션은 [persistence.md](persistence.md) 참고 — Flyway로 이미 해결되어 더 이상 갭이 아니다.)

`aws`/`ses` 네임스페이스는 이미 `@ConfigurationProperties` + `@Validated`로 관심사별 분리와 Fail-fast 검증을 모두 갖췄다. **남은 갭은 `jwt.secret` 하나뿐이다** — 여전히 `@Value("\${jwt.secret}")`으로 개별 필드에 주입되며, 전용 `data class`/Fail-fast 검증이 없다. 아래에서 실제 코드와 이 갭을 함께 정리한다.

---

## 관심사별 설정 — `@ConfigurationProperties` + `data class` (실제 코드)

Kotlin에서는 관심사별 설정을 **불변 `data class`**로 표현하고 `@ConfigurationProperties`로 바인딩한다. Java의 setter 기반 `@ConfigurationProperties` 클래스나 Lombok `@Data`가 필요 없다.

```kotlin
// config/AwsProperties.kt — 실제 코드
package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "aws")
data class AwsProperties(
    @field:NotBlank
    val region: String,
    val endpointUrl: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
)
```

```kotlin
// config/SesProperties.kt — 실제 코드
package com.example.accountservice.config

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "ses")
data class SesProperties(
    @field:NotBlank
    @field:Email
    val senderEmail: String,
)
```

**네임스페이스는 `app.*`가 아니라 `aws`/`ses`다** — 이 저장소는 root가 예시로 드는 `app.database`/`app.jwt` 같은 공통 네임스페이스 접두어를 쓰지 않고, 관심사 이름을 그대로 최상위 prefix로 쓴다. 또한 `DatabaseProperties`라는 클래스는 존재하지 않는다 — DB 연결 정보는 Spring Boot의 표준 `spring.datasource.*` 프로퍼티(relaxed binding)로 충분해 별도 `data class`로 감싸지 않았다.

`data class` 생성자 파라미터에 `@field:NotBlank`가 붙은 채 값이 비어 있으면 Spring Boot가 바인딩 시점에 **바인딩 실패 예외**를 던진다 — 이것이 곧 Fail-fast다. `AwsProperties.region`, `SesProperties.senderEmail`이 실제로 이 검증 대상이다.

`@ConfigurationProperties` 클래스는 `@ConfigurationPropertiesScan`(또는 컴포넌트 스캔 범위 안의 `@Component` 등록)으로 자동 등록되며, 생성자 주입으로 바로 사용한다:

```kotlin
// secret/infrastructure/SecretManagerConfig.kt — 실제 코드, AwsProperties 생성자 주입
@Configuration
class SecretManagerConfig(private val awsProperties: AwsProperties) {
    @Bean
    fun secretsManagerClient(): SecretsManagerClient {
        val builder = SecretsManagerClient.builder()
            .region(Region.of(awsProperties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.accessKeyId, awsProperties.secretAccessKey),
                ),
            )
        if (awsProperties.endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(awsProperties.endpointUrl))
        return builder.build()
    }
}
```

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — 실제 코드, SesProperties 생성자 주입
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,
) : NotificationService {
    override fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String) {
        val request = SendEmailRequest.builder()
            .source(sesProperties.senderEmail)
            // ...
            .build()
        // ...
    }
}
```

`@Value("\${SES_SENDER_EMAIL:no-reply@...}")`처럼 문자열 키를 여기저기 필드에 흩뿌리는 대신, `SesProperties` 하나를 생성자로 주입받아 IDE 자동완성과 컴파일 타임 타입 체크를 모두 얻는다.

---

## Fail-Fast — `@Validated` + Bean Validation (적용 완료: aws/ses, 남은 갭: jwt)

`data class`에 Bean Validation 애노테이션을 붙이고 `@ConfigurationProperties` 클래스에 `@Validated`를 추가하면, 빈 문자열도 명시적으로 차단할 수 있다 — `AwsProperties`/`SesProperties`가 이미 이 패턴이다.

`AWS_REGION`이나 `SES_SENDER_EMAIL`이 빈 문자열이면 앱 기동 시 `ConfigurationPropertiesBindException`이 발생하고 프로세스가 즉시 종료된다 — root가 요구하는 "잘못된 설정으로 런타임에 실패하는 대신 기동 단계에서 즉시 실패"를 Spring Boot의 기본 메커니즘만으로 얻는다.

**`jwt.secret`은 이 검증 대상이 아니다.** `AuthService`/`JwtAuthenticationFilter` 모두 `@Value("\${jwt.secret}")`으로 개별 주입받으며, 빈 문자열이어도 앱은 정상 기동된다(서명/검증이 실패하는 런타임 시점에야 문제가 드러난다). 전용 `data class JwtProperties(@field:NotBlank val secret: String)` + `@ConfigurationProperties(prefix = "jwt")`로 감싸면 나머지 두 값과 동일한 Fail-fast를 얻을 수 있다 — 아직 반영되지 않은 갭이다.

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 | 이 저장소의 실제 상태 |
|------|----------|----------------------|
| 일반 설정 (호스트명, 포트, 리전) | 환경 변수 → `application.yml` → `@ConfigurationProperties` | `AwsProperties`/`SesProperties`로 적용 완료 |
| 민감값 (DB 비밀번호, JWT secret) | Secrets Manager → 상세는 [secret-manager.md](secret-manager.md) | `jwt.secret`만 해당 — prod 프로파일에서 `SecretsEnvironmentPostProcessor`가 Secrets Manager 조회 결과로 덮어쓴다 |

**프로덕션 프로파일에서 `jwt.secret`을 채우는 실제 메커니즘은 `@Bean`+`@Profile` 팩토리가 아니라 `EnvironmentPostProcessor`다.** `SecretsEnvironmentPostProcessor`(`secret/infrastructure/`)가 `ApplicationContext` 생성보다도 이른 시점에 `prod` 프로파일에서만 Secrets Manager를 조회해 `jwt.secret` 프로퍼티를 `Environment`에 주입한다 — 이후 `@Value("\${jwt.secret}")` 바인딩은 이 값을 그대로 사용하므로, `AuthService`/`JwtAuthenticationFilter` 코드는 로컬/prod를 구분하는 어떤 분기도 갖지 않는다. 상세는 [secret-manager.md](secret-manager.md) 참조.

---

## 설정 접근 패턴

설정 값(`AwsProperties`, `SesProperties` 등)은 Infrastructure 레이어(Repository 구현체, `@Configuration` 클래스)에서만 주입받는다. Domain/Application 레이어의 생성자에 `@ConfigurationProperties` 타입을 주입하지 않는다.

```kotlin
// 올바른 방식 — Infrastructure 레이어에서 설정 접근
@Component
class NotificationServiceImpl(private val sesProperties: SesProperties, /* ... */) : NotificationService { /* ... */ }

// 잘못된 방식 — Application Service가 설정에 직접 의존
@Service
class CreateAccountService(private val sesProperties: SesProperties) { /* 금지 */ }
```

---

## 원칙 요약

- **`@ConfigurationProperties` + `data class`**: `AwsProperties`/`SesProperties`로 관심사별 분리 완료. 기본값 없는 필드(`region`, `senderEmail`)로 Fail-fast를 얻는다.
- **`@Validated` + Bean Validation**: `aws`/`ses`는 `@field:NotBlank` 등으로 이미 적용됨. **`jwt.secret`은 여전히 `@Value` 기반 — 전용 `JwtProperties` 도입이 남은 갭.**
- **민감값은 Secrets Manager**: `jwt.secret`만 해당하며, `SecretsEnvironmentPostProcessor`로 prod 프로파일에서 실제 연동됨 — [secret-manager.md](secret-manager.md) 참조.
- **설정 접근은 Infrastructure 레이어**: Domain/Application은 설정 타입에 의존하지 않는다.
- **`.env`는 로컬 전용**: 커밋하지 않는다 ([local-dev.md](local-dev.md) 참조).

### 관련 문서

- [container.md](container.md) — 환경 변수 주입 방법
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
