# Secret 관리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root secret-manager.md](../../../../docs/architecture/secret-manager.md) 참조.

## 현재 상태 — 환경 변수 직접 주입, Secrets Manager 미연동

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — 실제 코드
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    @Value("\${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}") private val senderEmail: String,
) : NotificationService
```

`SES_SENDER_EMAIL`은 민감값이 아니라 발신자 이메일이므로 환경 변수로 충분하지만, DB 비밀번호나 JWT secret처럼 실제로 민감한 값은 이 저장소에 아직 없다(인증 자체가 미구현 — [authentication.md](authentication.md) 참조). Secrets Manager 연동도 없다. 아래는 [config.md](config.md)의 "민감값 — 환경 변수 vs Secrets Manager" 원칙을 Kotlin/Spring으로 구현하는 방법이다.

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

`SesClient`(→ [SesConfig.kt](../../examples/src/main/kotlin/com/example/accountservice/notification/infrastructure/SesConfig.kt))와 동일한 방식으로 `SecretsManagerClient`도 `AWS_ENDPOINT_URL`이 있으면 LocalStack, 없으면 실제 AWS로 분기하는 `@Configuration` Bean을 둔다.

```kotlin
// secret/infrastructure/SecretManagerConfig.kt
@Configuration
class SecretManagerConfig {
    @Bean
    fun secretsManagerClient(
        @Value("\${AWS_REGION:us-east-1}") region: String,
        @Value("\${AWS_ACCESS_KEY_ID:test}") accessKeyId: String,
        @Value("\${AWS_SECRET_ACCESS_KEY:test}") secretAccessKey: String,
        @Value("\${AWS_ENDPOINT_URL:}") endpointUrl: String,
    ): SecretsManagerClient {
        val builder = SecretsManagerClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        return builder.build()
    }
}
```

`build.gradle.kts`에 추가 필요 (`examples/`에는 아직 없음, SES와 같은 SDK v2 버전 사용):

```kotlin
implementation("software.amazon.awssdk:secretsmanager:2.29.52")
```

---

## JSON 형태 시크릿 + `@ConfigurationProperties`로 바인딩

```kotlin
// Secrets Manager에 JSON으로 저장된 값을 JwtProperties(→ config.md)로 변환
val json = secretService.getSecret("app/jwt")
val jwtSecret = jacksonObjectMapper().readTree(json).get("secret").asText()
```

[config.md](config.md)에서 정의한 `data class JwtProperties`를 프로덕션 프로파일에서는 환경 변수 대신 `SecretService` 조회 결과로 채우는 `@Bean` 팩토리로 대체한다.

```kotlin
// config/JwtPropertiesConfig.kt — 제안
@Configuration
class JwtPropertiesConfig(private val secretService: SecretService) {

    @Bean
    @Profile("prod")
    fun jwtProperties(): JwtProperties {
        val json = jacksonObjectMapper().readTree(secretService.getSecret("app/jwt"))
        return JwtProperties(secret = json.get("secret").asText())
    }

    @Bean
    @Profile("!prod")
    @ConfigurationProperties(prefix = "app.jwt")
    fun jwtPropertiesLocal(): JwtProperties = JwtProperties(secret = "")   // application.yml 값으로 바인딩됨
}
```

`@Profile("prod")`/`@Profile("!prod")`로 나누는 것이 Kotlin/Spring 관용이다 — Node의 `if (NODE_ENV === 'development')` 분기를 Spring의 프로파일 기반 Bean 선택으로 대체한다.

---

## 로컬 개발 — LocalStack

```yaml
# docker-compose.yml — SERVICES에 secretsmanager 추가 (local-dev.md 참고)
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

```bash
# localstack/init-secrets.sh — init-ses.sh와 같은 방식으로 추가
#!/bin/sh
set -e
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
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
