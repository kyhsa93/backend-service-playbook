# 환경 설정 관리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root config.md](../../../../docs/architecture/config.md) 참조.

## `@ConfigurationProperties` + `data class`

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

(`ddl-auto`/마이그레이션은 [persistence.md](persistence.md) 참고 — Flyway로 관리된다.)

`aws`/`ses` 네임스페이스는 `@ConfigurationProperties` + `@Validated`로 관심사별 분리와 Fail-fast 검증을 모두 갖췄다. **`jwt.secret`도 동일한 패턴을 따른다** — `JwtProperties(@field:NotBlank val secret: String)` + `@ConfigurationProperties(prefix = "jwt")`로 감싸져 있고, `AuthService`/`JwtAuthenticationFilter`는 `@Value` 대신 이 `JwtProperties`를 생성자 주입받는다. 아래에서 세 네임스페이스 모두를 실제 코드로 정리한다.

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

```kotlin
// config/JwtProperties.kt — 실제 코드
package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    @field:NotBlank
    val secret: String,
)
```

**네임스페이스는 `app.*`가 아니라 `aws`/`ses`/`jwt`다** — 이 저장소는 root가 예시로 드는 `app.database`/`app.jwt` 같은 공통 네임스페이스 접두어를 쓰지 않고, 관심사 이름을 그대로 최상위 prefix로 쓴다. 또한 `DatabaseProperties`라는 클래스는 존재하지 않는다 — DB 연결 정보는 Spring Boot의 표준 `spring.datasource.*` 프로퍼티(relaxed binding)로 충분해 별도 `data class`로 감싸지 않았다.

`data class` 생성자 파라미터에 `@field:NotBlank`가 붙은 채 값이 비어 있으면 Spring Boot가 바인딩 시점에 **바인딩 실패 예외**를 던진다 — 이것이 곧 Fail-fast다. `AwsProperties.region`, `SesProperties.senderEmail`, `JwtProperties.secret` 모두 실제로 이 검증 대상이다.

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
// account/infrastructure/notification/NotificationServiceImpl.kt — 실제 코드, SesProperties 생성자 주입
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

```kotlin
// auth/application/AuthService.kt — 실제 코드, JwtProperties 생성자 주입
@Service
class AuthService(jwtProperties: JwtProperties) {

    private val key: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    fun sign(userId: String): String =
        Jwts.builder()
            .subject(userId)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(key)
            .compact()
}
```

`@Value("\${SES_SENDER_EMAIL:no-reply@...}")`처럼 문자열 키를 여기저기 필드에 흩뿌리는 대신, `SesProperties`/`JwtProperties` 하나를 생성자로 주입받아 IDE 자동완성과 컴파일 타임 타입 체크를 모두 얻는다. `AuthService`와 `JwtAuthenticationFilter` 모두 이제 개별 `@Value("\${jwt.secret}")` 대신 `JwtProperties`를 생성자 주입받는다.

---

## Fail-Fast — `@Validated` + Bean Validation (aws/ses/jwt)

`data class`에 Bean Validation 애노테이션을 붙이고 `@ConfigurationProperties` 클래스에 `@Validated`를 추가하면, 빈 문자열도 명시적으로 차단할 수 있다 — `AwsProperties`/`SesProperties`/`JwtProperties` 모두 이 패턴이다.

`AWS_REGION`이나 `SES_SENDER_EMAIL`, `JWT_SECRET`이 빈 문자열이면 앱 기동 시 `ConfigurationPropertiesBindException`이 발생하고 프로세스가 즉시 종료된다 — root가 요구하는 "잘못된 설정으로 런타임에 실패하는 대신 기동 단계에서 즉시 실패"를 Spring Boot의 기본 메커니즘만으로 얻는다.

**`jwt.secret`도 이제 이 검증 대상이다.** `AuthService`/`JwtAuthenticationFilter` 모두 `JwtProperties`를 생성자로 주입받으며, `AccountServiceApplication`이 `@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class)`로 세 클래스를 함께 등록한다 — 빈 문자열이면 나머지 두 값과 동일하게 기동 단계에서 즉시 실패한다.

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 | 이 저장소의 실제 상태 |
|------|----------|----------------------|
| 일반 설정 (호스트명, 포트, 리전) | 환경 변수 → `application.yml` → `@ConfigurationProperties` | `AwsProperties`/`SesProperties` |
| 민감값 (DB 비밀번호, JWT secret) | Secrets Manager → 상세는 [secret-manager.md](secret-manager.md) | `jwt.secret`만 해당 — prod 프로파일에서 `SecretsEnvironmentPostProcessor`가 Secrets Manager 조회 결과로 덮어쓴다 |

**프로덕션 프로파일에서 `jwt.secret`을 채우는 실제 메커니즘은 `@Bean`+`@Profile` 팩토리가 아니라 `EnvironmentPostProcessor`다.** `SecretsEnvironmentPostProcessor`(`secret/infrastructure/`)가 `ApplicationContext` 생성보다도 이른 시점에 `prod` 프로파일에서만 Secrets Manager를 조회해 `jwt.secret` 프로퍼티를 `Environment`에 주입한다 — 이후 `JwtProperties`의 `@ConfigurationProperties(prefix = "jwt")` 바인딩은 이 값을 그대로 사용하므로, `AuthService`/`JwtAuthenticationFilter` 코드는 로컬/prod를 구분하는 어떤 분기도 갖지 않는다. 상세는 [secret-manager.md](secret-manager.md) 참조.

---

## 설정 접근 패턴

설정 값(`AwsProperties`, `SesProperties` 등)은 Infrastructure 레이어(Repository 구현체, `@Configuration` 클래스)에서만 주입받는다. Domain/Application 레이어의 생성자에 `@ConfigurationProperties` 타입을 주입하지 않는다. 같은 원칙을 harness `no-direct-env-access-outside-config` 규칙이 더 직접적인 형태로 검사한다 — `domain/`, `application/`에서 `System.getenv(...)`를 직접 호출하면 실패, `config/`(`@ConfigurationProperties` 클래스)와 `infrastructure/`만 환경 변수에 접근할 수 있다.

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

- **`@ConfigurationProperties` + `data class`**: `AwsProperties`/`SesProperties`/`JwtProperties`로 관심사별 분리 완료. 기본값 없는 필드(`region`, `senderEmail`, `secret`)로 Fail-fast를 얻는다.
- **`@Validated` + Bean Validation**: `aws`/`ses`/`jwt` 모두 `@field:NotBlank` 등으로 검증한다.
- **민감값은 Secrets Manager**: `jwt.secret`만 해당하며, `SecretsEnvironmentPostProcessor`로 prod 프로파일에서 실제 연동됨 — [secret-manager.md](secret-manager.md) 참조.
- **설정 접근은 Infrastructure 레이어**: Domain/Application은 설정 타입에 의존하지 않는다.
- **`.env`는 로컬 전용**: 커밋하지 않는다 ([local-dev.md](local-dev.md) 참조).

### 관련 문서

- [container.md](container.md) — 환경 변수 주입 방법
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
