# Environment Configuration Management — Kotlin Spring Boot

> For the framework-agnostic principles, see [root config.md](../../../../docs/architecture/config.md).

## `@ConfigurationProperties` + `data class`

The current `examples/src/main/resources/application.yml`:

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

(For `ddl-auto`/migrations, see [persistence.md](persistence.md) — managed via Flyway.)

The `aws`/`ses` namespaces have both per-concern separation and fail-fast validation via `@ConfigurationProperties` + `@Validated`. **`jwt.secret` follows the same pattern too** — it's wrapped in `JwtProperties(@field:NotBlank val secret: String)` + `@ConfigurationProperties(prefix = "jwt")`, and `AuthService`/`JwtAuthenticationFilter` constructor-inject this `JwtProperties` instead of using `@Value`. Below, all three namespaces are laid out with the actual code.

---

## Per-concern configuration — `@ConfigurationProperties` + `data class` (actual code)

In Kotlin, per-concern configuration is expressed as an **immutable `data class`** and bound with `@ConfigurationProperties`. There's no need for a setter-based `@ConfigurationProperties` class like in Java, or Lombok's `@Data`.

```kotlin
// config/AwsProperties.kt — actual code
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
// config/SesProperties.kt — actual code
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
// config/JwtProperties.kt — actual code
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

Two more namespaces follow the same per-concern shape but deliberately opt out of `@Validated`
fail-fast: `llm` (`LlmProperties` — `ollama-base-url`/`model`, for
`payment/infrastructure/RefundReasonClassifierImpl.kt`) and `fraud-scorer` (`FraudScorerProperties`
— `mode`/`base-url`, for `payment/infrastructure/RefundFraudRiskScorerNativeImpl.kt`/
`RefundFraudRiskScorerHttpImpl.kt`). Both already have sane defaults (`http://localhost:11434`/
`qwen2.5:1.5b`, `native`/`http://localhost:8000`), and a missing/blank value must never fail
application startup — an LLM classification or ML scoring outage is tolerated at runtime as a
non-blocking fallback (see each impl's `catch`/fallback-score handling), not a fail-fast condition
the way `aws`/`ses`/`jwt` are. `fraud-scorer.mode` additionally drives bean selection: each of
`RefundFraudRiskScorerNativeImpl`/`RefundFraudRiskScorerHttpImpl` is `@Component` +
`@ConditionalOnProperty(prefix = "fraud-scorer", name = ["mode"], havingValue = "native"|"http", ...)`,
so only the implementation `FRAUD_SCORER_MODE` selects is ever registered as the
`RefundFraudRiskScorer` bean.

**The namespaces are `aws`/`ses`/`jwt`/`llm`/`fraud-scorer`, not `app.*`** — this repository doesn't use a shared namespace prefix like the `app.database`/`app.jwt` the root gives as an example; it uses the concern's name directly as the top-level prefix. There's also no `DatabaseProperties` class — DB connection info is sufficiently handled by Spring Boot's standard `spring.datasource.*` properties (relaxed binding), so it wasn't wrapped in a separate `data class`.

If a `data class` constructor parameter annotated with `@field:NotBlank` has an empty value, Spring Boot throws a **binding failure exception** at bind time — that's fail-fast right there. `AwsProperties.region`, `SesProperties.senderEmail`, and `JwtProperties.secret` are all actually subject to this validation.

`@ConfigurationProperties` classes are auto-registered via `@ConfigurationPropertiesScan` (or `@Component` registration within the component-scan range), and used directly via constructor injection:

```kotlin
// secret/infrastructure/SecretManagerConfig.kt — actual code, AwsProperties constructor injection
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
// notification/infrastructure/NotificationServiceImpl.kt — actual code, SesProperties constructor injection
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
// auth/application/AuthService.kt — actual code, JwtProperties constructor injection
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

Instead of scattering string keys like `@Value("\${SES_SENDER_EMAIL:no-reply@...}")` across various fields, a single `SesProperties`/`JwtProperties` is constructor-injected, gaining both IDE autocompletion and compile-time type checking. Both `AuthService` and `JwtAuthenticationFilter` now constructor-inject `JwtProperties` instead of an individual `@Value("\${jwt.secret}")`.

---

## Fail-fast — `@Validated` + Bean Validation (aws/ses/jwt)

Attaching Bean Validation annotations to a `data class` and adding `@Validated` to the `@ConfigurationProperties` class lets you explicitly block empty strings too — `AwsProperties`/`SesProperties`/`JwtProperties` all follow this pattern.

If `AWS_REGION`, `SES_SENDER_EMAIL`, or `JWT_SECRET` is an empty string, a `ConfigurationPropertiesBindException` is raised at app startup and the process terminates immediately — the root's requirement of "fail immediately at startup instead of failing at runtime from bad config" is achieved using only Spring Boot's default mechanism.

**`jwt.secret` is now subject to this validation too.** Both `AuthService`/`JwtAuthenticationFilter` constructor-inject `JwtProperties`, and `AccountServiceApplication` registers all three classes together via `@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class)` — an empty string fails immediately at startup, the same as the other two values.

---

## Sensitive values — environment variables vs. Secrets Manager

| Item | Recommended approach | This repository's actual state |
|------|----------|----------------------|
| General config (hostname, port, region) | env var → `application.yml` → `@ConfigurationProperties` | `AwsProperties`/`SesProperties` |
| Sensitive values (DB password, JWT secret) | Secrets Manager → see [secret-manager.md](secret-manager.md) for details | Only `jwt.secret` applies — `SecretsEnvironmentPostProcessor` overwrites it with a Secrets Manager lookup result under the prod profile |

**The actual mechanism that populates `jwt.secret` in the production profile is an `EnvironmentPostProcessor`, not a `@Bean`+`@Profile` factory.** `SecretsEnvironmentPostProcessor` (`secret/infrastructure/`) queries Secrets Manager only under the `prod` profile, at a point even earlier than `ApplicationContext` creation, and injects the `jwt.secret` property into the `Environment` — the subsequent `@ConfigurationProperties(prefix = "jwt")` binding on `JwtProperties` then just uses this value as-is, so the `AuthService`/`JwtAuthenticationFilter` code has no branching at all to distinguish local from prod. See [secret-manager.md](secret-manager.md) for details.

---

## Configuration access pattern

Configuration values (`AwsProperties`, `SesProperties`, etc.) are only injected in the Infrastructure layer (Repository implementations, `@Configuration` classes). A `@ConfigurationProperties` type is never injected into a Domain/Application layer constructor. The harness's `no-direct-env-access-outside-config` rule checks the same principle in a more direct form — calling `System.getenv(...)` directly in `domain/`, `application/` fails; only `config/` (`@ConfigurationProperties` classes) and `infrastructure/` may access environment variables.

```kotlin
// correct — accessing configuration in the Infrastructure layer
@Component
class NotificationServiceImpl(private val sesProperties: SesProperties, /* ... */) : NotificationService { /* ... */ }

// incorrect — an Application Service depends directly on configuration
@Service
class CreateAccountService(private val sesProperties: SesProperties) { /* forbidden */ }
```

---

## Principle summary

- **`@ConfigurationProperties` + `data class`**: per-concern separation complete via `AwsProperties`/`SesProperties`/`JwtProperties`. Fields with no default value (`region`, `senderEmail`, `secret`) provide fail-fast.
- **`@Validated` + Bean Validation**: `aws`/`ses`/`jwt` are all validated with `@field:NotBlank` etc.
- **Sensitive values go through Secrets Manager**: only `jwt.secret` applies, actually wired up under the prod profile via `SecretsEnvironmentPostProcessor` — see [secret-manager.md](secret-manager.md).
- **Configuration access stays in the Infrastructure layer**: Domain/Application never depend on a configuration type.
- **`.env` is local-only**: never committed (see [local-dev.md](local-dev.md)).

### Related documents

- [container.md](container.md) — how environment variables are injected
- [secret-manager.md](secret-manager.md) — details of Secrets Manager lookup/caching
- [local-dev.md](local-dev.md) — setting up the local dev environment
