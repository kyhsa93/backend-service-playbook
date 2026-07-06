# 환경 설정 관리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root config.md](../../../../docs/architecture/config.md) 참조.

## 알려진 갭

현재 `examples/src/main/resources/application.yml`은 3줄뿐이다:

```yaml
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

관심사별 설정 분리도, 기동 시 Fail-fast 검증도 없다. `SES_SENDER_EMAIL`, `AWS_REGION` 등은 `@Value("\${...:기본값}")`으로 개별 필드에 흩어져 주입되며, 값이 비어 있어도 앱은 정상 기동된다. 아래는 Kotlin/Spring Boot에서 이 갭을 메우는 올바른 패턴이다.

---

## 관심사별 설정 — `@ConfigurationProperties` + `data class`

Kotlin에서는 관심사별 설정을 **불변 `data class`**로 표현하고 `@ConfigurationProperties`로 바인딩한다. Java의 setter 기반 `@ConfigurationProperties` 클래스나 Lombok `@Data`가 필요 없다.

```kotlin
// config/DatabaseProperties.kt
package com.example.accountservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.database")
data class DatabaseProperties(
    val host: String,
    val port: Int = 5432,
    val username: String,
    val password: String,
    val name: String,
)
```

```kotlin
// config/JwtProperties.kt
@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    val secret: String,
    val expirationSeconds: Long = 3600,
)
```

```kotlin
// config/SesProperties.kt
@ConfigurationProperties(prefix = "app.ses")
data class SesProperties(
    val senderEmail: String,
    val region: String = "us-east-1",
)
```

`data class` 생성자 파라미터에 값을 넣지 않으면(필수 필드에 기본값이 없으면) Spring Boot가 바인딩 시점에 **바인딩 실패 예외**를 던진다 — 이것이 곧 Fail-fast다. `host`, `username`, `password`, `name`, `secret`, `senderEmail`처럼 기본값이 없는 필드는 실수로 비워두면 기동 자체가 실패한다.

한 곳에서 전체를 활성화하고 타입-세이프하게 주입받는다:

```kotlin
// AccountServiceApplication.kt
@SpringBootApplication
@EnableConfigurationProperties(DatabaseProperties::class, JwtProperties::class, SesProperties::class)
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
```

```kotlin
// notification/infrastructure/SesConfig.kt — 개선된 형태
@Configuration
class SesConfig(private val sesProperties: SesProperties) {

    @Bean
    fun sesClient(): SesClient = SesClient.builder()
        .region(Region.of(sesProperties.region))
        .build()
}
```

`@Value("\${SES_SENDER_EMAIL:no-reply@...}")`처럼 문자열 키를 여기저기 필드에 흩뿌리는 대신, `SesProperties` 하나를 생성자로 주입받아 IDE 자동완성과 컴파일 타임 타입 체크를 모두 얻는다.

```yaml
# application.yml — 관심사별로 네임스페이스 분리
app:
  database:
    host: ${DATABASE_HOST:localhost}
    port: ${DATABASE_PORT:5432}
    username: ${DATABASE_USER:dev}
    password: ${DATABASE_PASSWORD:}
    name: ${DATABASE_NAME:app}
  jwt:
    secret: ${JWT_SECRET:}
  ses:
    sender-email: ${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}
    region: ${AWS_REGION:us-east-1}

spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

**기본값 설정 원칙**: 로컬 개발에서 동작하는 값(`localhost`, `dev`)은 YAML에 기본값으로 둔다. 프로덕션에서 비어 있으면 안 되는 값(`DATABASE_PASSWORD`, `JWT_SECRET`)은 기본값을 빈 문자열로 두어, 값이 실제로 주입되지 않으면 `@ConfigurationProperties` 바인딩 검증(`@Validated` + Bean Validation)에서 걸러지도록 한다.

---

## Fail-Fast — `@Validated` + Bean Validation

`data class`에 Bean Validation 애노테이션을 붙이고 `@ConfigurationProperties` 클래스에 `@Validated`를 추가하면, 빈 문자열도 명시적으로 차단할 수 있다.

```kotlin
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    @field:NotBlank
    val secret: String,
    val expirationSeconds: Long = 3600,
)
```

`JWT_SECRET`이 빈 문자열이면 앱 기동 시 `ConfigurationPropertiesBindException`이 발생하고 프로세스가 즉시 종료된다 — root가 요구하는 "잘못된 설정으로 런타임에 실패하는 대신 기동 단계에서 즉시 실패"를 Spring Boot의 기본 메커니즘만으로 얻는다. 별도의 `validateEnv()` 함수나 `process.exit(1)` 호출을 직접 작성할 필요가 없다.

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 |
|------|----------|
| 일반 설정 (호스트명, 포트, 리전) | 환경 변수 → `application.yml` → `@ConfigurationProperties` |
| 민감값 (DB 비밀번호, JWT secret) | Secrets Manager → 상세는 [secret-manager.md](secret-manager.md) |

프로덕션 프로파일(`application-prod.yml`)에서는 `DatabaseProperties`/`JwtProperties`를 환경 변수 대신 `SecretService` 조회 결과로 채우는 `@Bean` 팩토리 메서드로 대체한다. 상세는 [secret-manager.md](secret-manager.md)의 "설정 팩토리에서 SecretService 사용" 참조.

---

## 설정 접근 패턴

설정 값(`DatabaseProperties` 등)은 Infrastructure 레이어(Repository 구현체, `@Configuration` 클래스)에서만 주입받는다. Domain/Application 레이어의 생성자에 `@ConfigurationProperties` 타입을 주입하지 않는다.

```kotlin
// 올바른 방식 — Infrastructure 레이어에서 설정 접근
@Configuration
class SesConfig(private val sesProperties: SesProperties) { /* ... */ }

// 잘못된 방식 — Application Service가 설정에 직접 의존
@Service
class CreateAccountService(private val sesProperties: SesProperties) { /* 금지 */ }
```

---

## 원칙 요약

- **`@ConfigurationProperties` + `data class`**: 관심사별로 나누고, 기본값 없는 필드로 Fail-fast를 자연스럽게 얻는다.
- **`@Validated` + Bean Validation**: 빈 문자열까지 막아야 하는 필드는 `@field:NotBlank` 등을 추가한다.
- **민감값은 Secrets Manager**: [secret-manager.md](secret-manager.md) 참조.
- **설정 접근은 Infrastructure 레이어**: Domain/Application은 설정 타입에 의존하지 않는다.
- **`.env`는 로컬 전용**: 커밋하지 않는다 ([local-dev.md](local-dev.md) 참조).

### 관련 문서

- [container.md](container.md) — 환경 변수 주입 방법
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
