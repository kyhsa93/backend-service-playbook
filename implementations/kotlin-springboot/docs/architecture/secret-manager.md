# Secret 관리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root secret-manager.md](../../../../docs/architecture/secret-manager.md) 참조.

## 적용 완료 — AWS Secrets Manager + TTL 캐시

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — 실제 코드
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,
) : NotificationService
```

`SES_SENDER_EMAIL`(→ `SesProperties.senderEmail`, [config.md](config.md) 참조)은 민감값이 아니라 발신자 이메일이므로 환경 변수로 충분하다. 실제로 민감한 값은 **JWT 서명 secret**이다 — 인증은 이미 구현되어 있고([authentication.md](authentication.md) 참조), 그 secret은 아래 `SecretService`/`SecretsEnvironmentPostProcessor`를 통해 prod 프로파일에서 AWS Secrets Manager로부터 조회된다. 아래는 [config.md](config.md)의 "민감값 — 환경 변수 vs Secrets Manager" 원칙을 Kotlin/Spring으로 구현한 실제 코드다.

---

## SecretService — Technical Service로 추상화

`notification/`의 `NotificationService`/`NotificationServiceImpl` 쌍과 동일한 구조를 재사용한다.

```kotlin
// secret/application/service/SecretService.kt
package com.example.accountservice.secret.application.service

interface SecretService {
    fun getSecret(secretId: String): String
}
```

```kotlin
// secret/infrastructure/SecretServiceImpl.kt — AWS Secrets Manager + TTL 캐시
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

`ConcurrentHashMap`을 쓰는 이유: Tomcat은 요청마다 스레드를 배정하므로 여러 스레드가 동시에 `getSecret()`을 호출할 수 있다 — `HashMap`은 스레드 세이프하지 않아 동시 갱신 시 캐시가 깨질 수 있다. `data class CacheEntry`로 값과 만료 시각을 묶어 Kotlin다운 불변 캐시 항목을 표현한다.

`SesClient`(→ [SesConfig.kt](../../examples/src/main/kotlin/com/example/accountservice/notification/infrastructure/SesConfig.kt))와 동일한 방식으로 `SecretsManagerClient`도 `AWS_ENDPOINT_URL`이 있으면 LocalStack, 없으면 실제 AWS로 분기하는 `@Configuration` Bean을 둔다. `AwsProperties`([config.md](config.md) 참조)를 생성자로 주입받아 개별 `@Value` 대신 타입-세이프하게 값을 얻는다.

```kotlin
// secret/infrastructure/SecretManagerConfig.kt — 실제 코드
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

`build.gradle.kts`에 이미 포함되어 있다 (SES와 같은 SDK v2 버전):

```kotlin
implementation("software.amazon.awssdk:secretsmanager:2.29.52")
```

---

## JWT secret 주입 — `EnvironmentPostProcessor`(제안했던 `@Bean`+`@Profile` 팩토리가 아니다)

[config.md](config.md)가 정의하는 `data class JwtProperties` + `@Bean`/`@Profile` 팩토리 조합은 이 저장소가 실제로 택한 방식이 아니다. 실제 메커니즘은 `SecretsEnvironmentPostProcessor`(`secret/infrastructure/`)다 — `ApplicationContext`가 만들어지기 전, 가장 이른 시점에 `Environment`에 직접 프로퍼티를 주입한다.

```kotlin
// secret/infrastructure/SecretsEnvironmentPostProcessor.kt — 실제 코드
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

`prod` 프로파일에서만 동작해 로컬/테스트 기동 속도와 네트워크 의존성에 영향을 주지 않는다. `EnvironmentPostProcessor`는 `SecretService`(위 `SecretServiceImpl`)를 거치지 않고 `SecretsManagerClient`를 직접 만들어 쓴다 — 이 시점에는 아직 Spring `ApplicationContext`가 존재하지 않아 `@Component`로 등록된 `SecretServiceImpl`을 DI로 주입받을 수 없기 때문이다(`SecretService`는 Bean이 이미 뜬 이후 애플리케이션 코드에서 시크릿을 조회할 때 쓰는 별도 경로다).

이 `EnvironmentPostProcessor`를 Spring Boot가 기동 시점에 인식하게 하려면 `META-INF/spring.factories`에 등록해야 한다:

```
# src/main/resources/META-INF/spring.factories — 실제 코드
org.springframework.boot.env.EnvironmentPostProcessor=com.example.accountservice.secret.infrastructure.SecretsEnvironmentPostProcessor
```

이렇게 등록된 `jwt.secret` 프로퍼티는 `application-prod.yml`이나 기본 `application.yml`의 `jwt.secret` 값보다 우선순위가 높은 프로퍼티 소스(`addFirst`)로 들어가므로, `AuthService`/`JwtAuthenticationFilter`의 `@Value("\${jwt.secret}")` 바인딩이 이후 이 값을 그대로 사용한다 — 두 클래스는 prod/로컬을 구분하는 어떤 분기도 갖지 않는다.

---

## 로컬 개발 — LocalStack (적용 완료)

```yaml
# docker-compose.yml — 실제 코드
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

```bash
# localstack/init-secrets.sh — 실제 코드
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret-local-dev-secret"}'
```

---

## 원칙

- **민감값은 환경 변수에 직접 넣지 않는다**: 운영에서는 Secrets Manager, 로컬은 환경 변수/LocalStack.
- **TTL 캐시 필수**: `ConcurrentHashMap` + 만료 시각 — API 호출 비용/rate limit 회피.
- **`SecretService` 인터페이스로 추상화**: `NotificationService`와 동일한 Technical Service 패턴.
- **JSON으로 묶어 저장**: 논리적으로 연관된 값(DB 접속 정보 전체 등)은 시크릿 하나에 모은다.

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준, `@ConfigurationProperties`
- [directory-structure.md](directory-structure.md) — Technical Service 패턴 배치
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
