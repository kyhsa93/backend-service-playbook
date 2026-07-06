# Secret 관리 (Spring Boot / AWS Secrets Manager)

> 프레임워크 무관 원칙은 루트 [secret-manager.md](../../../../docs/architecture/secret-manager.md) 참고. [config.md](config.md)의 "민감값 — 환경 변수 vs Secrets Manager" 원칙의 구현 상세다.

## 현재 상태 — 알려진 gap

`notification/infrastructure/SesConfig.java`가 AWS 자격증명을 다루는 유일한 곳이지만, Secrets Manager를 전혀 사용하지 않는다:

```java
// SesConfig.java — 실제 코드
@Bean
public SesClient sesClient(
        @Value("${aws.access-key-id:test}") String accessKeyId,
        @Value("${aws.secret-access-key:test}") String secretAccessKey
) { /* ... */ }
```

자격증명은 환경 변수(`${aws.access-key-id:test}`)로만 주입되며, 기본값이 `test`/`test`라 운영 환경 변수 주입을 잊어도 기동은 되어버린다 — [config.md](config.md)에서 지적한 fail-fast 부재와 같은 근본 원인이다. Secrets Manager 클라이언트, TTL 캐시 어디에도 없다.

---

## `SecretService` — Technical Service로 추상화

`notification/application/service/NotificationService`(인터페이스) + `notification/infrastructure/NotificationServiceImpl`(구현체)가 이미 보여주는 Technical Service 패턴을 Secret 조회에도 동일하게 적용한다.

```java
// common/service/SecretService.java — 인터페이스 (제안)
public interface SecretService {
    String getSecret(String secretId);
}
```

```java
// common/infrastructure/SecretServiceImpl.java — AWS Secrets Manager 구현체 + TTL 캐시 (제안)
@Component
public class SecretServiceImpl implements SecretService {

    private final SecretsManagerClient client;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(5);

    public SecretServiceImpl(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl
    ) {
        var builder = SecretsManagerClient.builder().region(Region.of(region));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        this.client = builder.build();
    }

    @Override
    public String getSecret(String secretId) {
        CachedSecret cached = cache.get(secretId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        String value = client.getSecretValue(r -> r.secretId(secretId)).secretString();
        cache.put(secretId, new CachedSecret(value, Instant.now().plus(TTL)));
        return value;
    }

    private record CachedSecret(String value, Instant expiresAt) {}
}
```

- **`ConcurrentHashMap`으로 TTL 캐시**: Spring이 이 빈을 싱글턴으로 관리하므로 여러 요청 스레드가 동시에 `getSecret()`을 호출할 수 있다 — `HashMap`이 아니라 `ConcurrentHashMap`을 써야 스레드 안전하다. Caffeine(`com.github.ben-manes.caffeine:caffeine`) 같은 전용 캐시 라이브러리로 교체하면 만료 정책(size-based eviction 등)을 더 정교하게 제어할 수 있다.
- **동일 `endpointUrl` 분기 패턴**: `SesConfig`가 이미 쓰는 "endpoint-url 있으면 LocalStack, 없으면 실제 AWS" 패턴을 그대로 재사용한다 — [local-dev.md](local-dev.md) 참고.

---

## JSON 형태의 시크릿 사용

여러 값을 하나의 시크릿에 JSON으로 저장하고 키별로 접근한다 — 논리적으로 묶이는 값(DB 접속 정보 전체 등)은 API 호출 횟수를 줄이기 위해 하나의 시크릿에 모은다.

```java
// Secrets Manager 저장 값 예시
// secretId: "account-service/database"
// value: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

ObjectMapper objectMapper = new ObjectMapper();
JsonNode dbSecret = objectMapper.readTree(secretService.getSecret("account-service/database"));
String password = dbSecret.get("password").asText();
```

---

## `@ConfigurationProperties`와의 연결 — 기동 시 한 번만 조회

[config.md](config.md)가 설명하는 `@ConfigurationProperties` + `@Validated` 체계에 Secrets Manager 조회 값을 통합하려면, Spring의 `EnvironmentPostProcessor`를 사용해 `ApplicationContext` 준비 이전에 Secrets Manager 값을 `Environment`에 주입한다.

```java
// common/config/SecretsEnvironmentPostProcessor.java — 제안
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;   // 로컬/테스트는 스킵

        SecretsManagerClient client = SecretsManagerClient.create();
        String dbSecretJson = client.getSecretValue(r -> r.secretId("account-service/database")).secretString();
        Map<String, Object> dbSecret = new ObjectMapper().readValue(dbSecretJson, new TypeReference<>() {});

        Map<String, Object> props = Map.of(
                "spring.datasource.password", dbSecret.get("password"),
                "spring.datasource.username", dbSecret.get("username")
        );
        environment.getPropertySources().addFirst(new MapPropertySource("secretsManager", props));
    }
}
```

```
# META-INF/spring.factories — 등록
org.springframework.boot.env.EnvironmentPostProcessor=com.example.accountservice.common.config.SecretsEnvironmentPostProcessor
```

`EnvironmentPostProcessor`는 `ApplicationContext`가 완전히 준비되기 전, 가장 이른 시점에 실행되므로 이후의 `@ConfigurationProperties` 바인딩(`AwsProperties`, `SesProperties`, [config.md](config.md) 참고)이 이 값을 그대로 사용할 수 있다 — **운영 프로필에서만 실행**하도록 분기해 로컬/테스트 기동 속도에 영향을 주지 않는다.

---

## 로컬 개발 — LocalStack

```bash
# examples/localstack/init-secrets.sh — 추가 시 (제안)
awslocal secretsmanager create-secret \
  --name account-service/database \
  --secret-string '{"host":"database","port":"5432","username":"dev","password":"dev"}'
```

```yaml
# docker-compose.yml — SERVICES에 secretsmanager 추가 시
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

현재 `docker-compose.yml`의 `SERVICES: ses`에는 `secretsmanager`가 없다 — Secrets Manager를 도입하면 함께 추가해야 한다.

---

## 원칙

- **민감값은 환경 변수 기본값(`test`/`test`)에 영구히 의존하지 않는다**: 운영 환경은 Secrets Manager에서 조회한다.
- **TTL 캐시 필수**: Secrets Manager는 호출당 과금되므로 매 요청 조회를 피한다.
- **SecretService 인터페이스로 추상화**: `NotificationService`와 동일한 Technical Service 패턴.
- **논리적으로 묶이는 값은 하나의 시크릿에 JSON으로 저장**한다.
- **`EnvironmentPostProcessor`로 기동 초기에 주입**: `@ConfigurationProperties` 바인딩 이전에 값이 준비되어야 한다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준, `@ConfigurationProperties`
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
