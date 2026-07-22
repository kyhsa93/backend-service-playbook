# Secret Management — Kotlin Spring Boot

> For the framework-agnostic principles, see [root secret-manager.md](../../../../docs/architecture/secret-manager.md).

## AWS Secrets Manager + a TTL cache

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — actual code
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,
) : NotificationService
```

`SES_SENDER_EMAIL` (→ `SesProperties.senderEmail`, see [config.md](config.md)) is the sender's email, not a sensitive value, so an environment variable is sufficient. The actually sensitive value is the **JWT signing secret** — authentication is covered in [authentication.md](authentication.md), and that secret is looked up from AWS Secrets Manager under the prod profile via the `SecretService`/`SecretsEnvironmentPostProcessor` below. What follows is the actual Kotlin/Spring code implementing [config.md](config.md)'s "Sensitive values — environment variables vs. Secrets Manager" principle.

---

## SecretService — abstracted as a Technical Service

Reuses the same structure as `notification/`'s `NotificationService`/`NotificationServiceImpl` pair.

```kotlin
// secret/application/service/SecretService.kt
package com.example.accountservice.secret.application.service

interface SecretService {
    fun getSecret(secretId: String): String
}
```

```kotlin
// secret/infrastructure/SecretServiceImpl.kt — AWS Secrets Manager + a TTL cache
package com.example.accountservice.secret.infrastructure

import com.example.accountservice.secret.application.service.SecretService
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class SecretServiceImpl(private val client: SecretsManagerClient) : SecretService {

    private data class CacheEntry(val value: String, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttl = java.time.Duration.ofMinutes(5)

    override fun getSecret(secretId: String): String {
        cache[secretId]?.let { entry ->
            if (entry.expiresAt.isAfter(Instant.now())) return entry.value
        }

        val value = client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build()).secretString()
        cache[secretId] = CacheEntry(value, Instant.now().plus(ttl))
        return value
    }
}
```

Why `ConcurrentHashMap` is used: Tomcat assigns a thread per request, so multiple threads may call `getSecret()` concurrently — `HashMap` isn't thread-safe, so the cache could get corrupted on a concurrent update. `data class CacheEntry` bundles the value and expiration time together, expressing a Kotlin-native immutable cache entry.

The same way as `SesClient` (→ [SesConfig.kt](../../examples/src/main/kotlin/com/example/accountservice/notification/infrastructure/SesConfig.kt)), `SecretsManagerClient` also has a `@Configuration` Bean that branches to LocalStack if `AWS_ENDPOINT_URL` is set, or to real AWS otherwise. It constructor-injects `AwsProperties` (see [config.md](config.md)) to get the values type-safely instead of individual `@Value`s.

```kotlin
// secret/infrastructure/SecretManagerConfig.kt — actual code
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

Already included in `build.gradle.kts` (same SDK v2 version as SES):

```kotlin
implementation("software.amazon.awssdk:secretsmanager:2.29.52")
```

---

## Injecting the JWT secret — an `EnvironmentPostProcessor` (not the `@Bean`+`@Profile` factory that was proposed)

The `data class JwtProperties` + `@Bean`/`@Profile` factory combination [config.md](config.md) defines is not what this repository actually chose. The real mechanism is `SecretsEnvironmentPostProcessor` (`secret/infrastructure/`) — at the earliest possible point, before the `ApplicationContext` is even created, it injects the property directly into the `Environment`.

```kotlin
// secret/infrastructure/SecretsEnvironmentPostProcessor.kt — actual code
class SecretsEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return

        val region = environment.getProperty("AWS_REGION", "us-east-1")
        val endpointUrl = environment.getProperty("AWS_ENDPOINT_URL", "")
        val accessKeyId = environment.getProperty("AWS_ACCESS_KEY_ID", "test")
        val secretAccessKey = environment.getProperty("AWS_SECRET_ACCESS_KEY", "test")

        val builder = SecretsManagerClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        val client = builder.build()

        client.use {
            val json = it.getSecretValue { r -> r.secretId("app/jwt") }.secretString()
            val secret = jacksonObjectMapper().readTree(json).get("secret").asText()
            environment.propertySources.addFirst(MapPropertySource("secretsManager", mapOf("jwt.secret" to secret)))
        }
    }
}
```

It only runs under the `prod` profile, so it doesn't affect local/test startup speed or add a network dependency there. The `EnvironmentPostProcessor` builds and uses a `SecretsManagerClient` directly, bypassing `SecretService` (`SecretServiceImpl` above) — at this point the Spring `ApplicationContext` doesn't exist yet, so `SecretServiceImpl` (registered as a `@Component`) can't be DI-injected (`SecretService` is a separate path used to look up a secret from application code once the Beans are already up).

**A cross-language difference — the gating mechanism itself differs**: this repository gates on the Spring **profile** (`Profiles.of("prod")`), not an environment variable — java-springboot uses the same mechanism (`Profiles.of("prod")`). nestjs (`NODE_ENV !== 'production'`), go (`APP_ENV != "production"`), and fastapi (`APP_ENV == "production"`) gate on an environment variable value, and of those, fastapi has the opposite polarity from the other two. When consulting another language's documentation, don't assume the name and polarity correspond directly.

For Spring Boot to recognize this `EnvironmentPostProcessor` at startup, it must be registered in `META-INF/spring.factories`:

```
# src/main/resources/META-INF/spring.factories — actual code
org.springframework.boot.env.EnvironmentPostProcessor=com.example.accountservice.secret.infrastructure.SecretsEnvironmentPostProcessor
```

The `jwt.secret` property registered this way goes into a property source with higher priority (`addFirst`) than the `jwt.secret` value in `application-prod.yml` or the default `application.yml`, so `AuthService`/`JwtAuthenticationFilter`'s `@Value("\${jwt.secret}")` binding just uses this value afterward — neither class has any branching at all to distinguish prod from local.

---

## Local development — LocalStack

```yaml
# docker-compose.yml — actual code
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

```bash
# localstack/init-secrets.sh — actual code
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret-local-dev-secret"}'
```

---

## Principles

- **Never put a sensitive value directly into an environment variable**: in production use Secrets Manager, locally use environment variables/LocalStack.
- **A TTL cache is required**: `ConcurrentHashMap` + an expiration time — avoids the cost of API calls/rate limits.
- **Abstracted via the `SecretService` interface**: the same Technical Service pattern as `NotificationService`.
- **Stored bundled as JSON**: logically related values (an entire set of DB connection info, etc) are grouped into a single secret.

### Related documents

- [config.md](config.md) — the criteria for environment variables vs. Secrets Manager, `@ConfigurationProperties`
- [directory-structure.md](directory-structure.md) — Technical Service pattern placement
- [local-dev.md](local-dev.md) — the LocalStack-based local development environment
