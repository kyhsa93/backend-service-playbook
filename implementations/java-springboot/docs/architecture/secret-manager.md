# Secret 관리 (Spring Boot / AWS Secrets Manager)

> 프레임워크 무관 원칙은 루트 [secret-manager.md](../../../../docs/architecture/secret-manager.md) 참고. [config.md](config.md)의 "민감값 — 환경 변수 vs Secrets Manager" 원칙의 구현 상세다.

## 현재 상태 — `SesConfig`는 여전히 환경 변수만 사용

`common/service/SecretService`(인터페이스) + `common/infrastructure/SecretServiceImpl`(AWS Secrets Manager 구현체 + TTL 캐시)이 구현되어 있다 — 아래 "`SecretService` — Technical Service로 추상화" 절이 실제 코드다. `common/config/SecretsEnvironmentPostProcessor`도 기동 초기에 Secrets Manager 값을 `Environment`에 주입한다(아래 참고).

다만 `account/infrastructure/notification/SesConfig.java`는 `SecretService`를 거치지 않고 여전히 `AwsProperties`(환경 변수 기반, [config.md](config.md) 참고)로만 AWS 자격증명을 받는다:

```java
// SesConfig.java — 실제 코드
@Bean
public SesClient sesClient() {
    SesClientBuilder builder = SesClient.builder()
            .region(Region.of(awsProperties.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())));
    /* ... */
}
```

SES 자격증명 자체는 IAM 정책으로 다루는 것이 일반적이라(SES는 "시크릿"이라기보다 리전/엔드포인트 설정에 가깝다) 이 저장소는 `SesConfig`를 `SecretService` 경유로 바꾸지 않았다 — `SecretService`는 현재 `SecretsEnvironmentPostProcessor`가 JWT secret을 조회하는 데만 쓰인다(아래 참고). 여러 값을 하나의 시크릿으로 묶어 관리하고 싶다면(예: DB 접속 정보 전체) 아래 "JSON 형태의 시크릿 사용" 패턴을 그대로 확장한다.

---

## `SecretService` — Technical Service로 추상화

`account/application/service/NotificationService`(인터페이스) + `account/infrastructure/notification/NotificationServiceImpl`(구현체)가 보여주는 Technical Service 패턴을 Secret 조회에도 동일하게 적용했다.

```java
// common/service/SecretService.java — 실제 코드
public interface SecretService {
    String getSecret(String secretId);
}
```

```java
// common/infrastructure/SecretServiceImpl.java — 실제 코드, AWS Secrets Manager 구현체 + TTL 캐시
@Component
public class SecretServiceImpl implements SecretService {

    private final SecretsManagerClient client;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(5);

    public SecretServiceImpl(AwsProperties awsProperties) {
        var builder = SecretsManagerClient.builder().region(Region.of(awsProperties.region()));
        if (awsProperties.endpointUrl() != null && !awsProperties.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl()));
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
- **`AwsProperties`를 그대로 재사용**: `region`/`endpointUrl` 조회를 개별 `@Value`가 아니라 [config.md](config.md)가 정의하는 `AwsProperties`(`@ConfigurationProperties`) 빈을 생성자로 주입받아 처리한다 — `SesConfig`가 쓰는 "endpoint-url 있으면 LocalStack, 없으면 실제 AWS" 패턴과 동일하다([local-dev.md](local-dev.md) 참고).
- **현재 호출자는 하나뿐**: `SecretService`를 실제로 호출하는 곳은 아래 `SecretsEnvironmentPostProcessor`뿐이다 — Application/Infrastructure 코드에서 직접 `secretService.getSecret(...)`을 호출하는 지점은 아직 없다. 새로운 민감값이 필요해지면 이 인터페이스를 그대로 재사용한다.

---

## JSON 형태의 시크릿 사용

여러 값을 하나의 시크릿에 JSON으로 저장하고 키별로 접근한다 — 논리적으로 묶이는 값(DB 접속 정보 전체 등)은 API 호출 횟수를 줄이기 위해 하나의 시크릿에 모은다.

```java
// 가상의 예시 — 이 저장소가 실제로 갖는 시크릿은 아래 "@ConfigurationProperties와의 연결" 절의 app/jwt 하나뿐이다
// secretId: "account-service/database"
// value: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

ObjectMapper objectMapper = new ObjectMapper();
JsonNode dbSecret = objectMapper.readTree(secretService.getSecret("account-service/database"));
String password = dbSecret.get("password").asText();
```

---

## `@ConfigurationProperties`와의 연결 — 기동 시 한 번만 조회

[config.md](config.md)가 설명하는 `@ConfigurationProperties` + `@Validated` 체계에 Secrets Manager 조회 값을 통합하기 위해, Spring의 `EnvironmentPostProcessor`를 사용해 `ApplicationContext` 준비 이전에 Secrets Manager 값을 `Environment`에 주입한다. 이 저장소가 실제로 관리하는 시크릿은 JWT 서명 키(`app/jwt`) 하나뿐이다 — DB 접속 정보는 Secrets Manager로 옮기지 않았다(Postgres는 `SPRING_DATASOURCE_*` 환경 변수로만 구성됨, [local-dev.md](local-dev.md) 참고).

```java
// common/config/SecretsEnvironmentPostProcessor.java — 실제 코드
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;   // 로컬/테스트는 스킵

        String region = environment.getProperty("aws.region", "us-east-1");
        String endpointUrl = environment.getProperty("aws.endpoint-url", "");

        var builder = SecretsManagerClient.builder().region(Region.of(region));
        if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        SecretsManagerClient client = builder.build();

        try {
            String jwtSecretJson = client.getSecretValue(r -> r.secretId("app/jwt")).secretString();
            Map<String, Object> jwtSecret = new ObjectMapper().readValue(jwtSecretJson, new TypeReference<>() {});

            Map<String, Object> props = Map.of("jwt.secret", jwtSecret.get("secret"));
            environment.getPropertySources().addFirst(new MapPropertySource("secretsManager", props));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load secrets from Secrets Manager", e);
        } finally {
            client.close();
        }
    }
}
```

```
# META-INF/spring.factories — 실제 코드, 등록
org.springframework.boot.env.EnvironmentPostProcessor=com.example.accountservice.common.config.SecretsEnvironmentPostProcessor
```

`EnvironmentPostProcessor`는 `ApplicationContext`가 완전히 준비되기 전, 가장 이른 시점에 실행되므로 이후 `SecurityConfig`의 `@Value("${jwt.secret}")` 바인딩([authentication.md](authentication.md) 참고)이 이 값을 그대로 사용한다 — **운영 프로필에서만 실행**하도록 분기해 로컬/테스트 기동 속도에 영향을 주지 않는다. 조회 실패 시 `IllegalStateException`을 던져 애플리케이션 기동 자체를 중단시킨다 — JWT secret 없이는 서비스가 뜨지 않는 것이 올바른 fail-fast다.

**언어 간 차이 — 게이팅 메커니즘 자체가 다르다**: 이 저장소는 환경 변수가 아니라 Spring **profile**(`Profiles.of("prod")`)로 게이팅한다 — `logback-spring.xml`([observability.md](observability.md) 참고)도 같은 `prod`/`!prod` 프로필로 통일되어 있다. kotlin-springboot도 동일한 메커니즘(`Profiles.of("prod")`)을 쓴다. nestjs(`NODE_ENV !== 'production'`), go(`APP_ENV != "production"`), fastapi(`APP_ENV == "production"`)는 환경 변수 값으로 게이팅하며, 그중 fastapi는 나머지 둘과 극성이 반대다. 다른 언어 문서를 참고할 때 이름과 극성이 그대로 대응된다고 가정하지 않는다.

DB 접속 정보(`spring.datasource.username`/`password`)를 Secrets Manager로 옮기고 싶다면, 이 클래스의 패턴을 그대로 확장해 `secretId`만 `account-service/database`로 바꾸고 `props`에 `spring.datasource.*` 키를 추가하면 된다 — 현재는 도입되지 않았다.

---

## 로컬 개발 — LocalStack

```bash
# examples/localstack/init-secrets.sh — 실제 코드
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret-local-dev-secret"}'
```

```yaml
# docker-compose.yml — 실제 코드
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

`docker-compose.yml`의 `SERVICES`에 `secretsmanager`가 포함되어 있고, `init-secrets.sh`가 LocalStack 컨테이너 기동 시 `app/jwt` 시크릿을 자동 생성한다 — 로컬에서도 운영과 동일한 Secrets Manager 경로를 그대로 탄다(단, `SPRING_PROFILES_ACTIVE=prod`일 때만 `SecretsEnvironmentPostProcessor`가 실제로 조회한다).

---

## 원칙

- **민감값은 환경 변수 기본값(`test`/`test`)에 영구히 의존하지 않는다**: 운영 환경은 Secrets Manager(`app/jwt`)에서 조회한다.
- **TTL 캐시 필수**: Secrets Manager는 호출당 과금되므로 매 요청 조회를 피한다 — `SecretServiceImpl`이 이를 구현한다.
- **SecretService 인터페이스로 추상화**: `NotificationService`와 동일한 Technical Service 패턴을 따른다.
- **논리적으로 묶이는 값은 하나의 시크릿에 JSON으로 저장**한다.
- **`EnvironmentPostProcessor`로 기동 초기에 주입**: `@ConfigurationProperties`/`@Value` 바인딩 이전에 값이 준비되어야 한다 — `SecretsEnvironmentPostProcessor`가 이를 담당한다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준, `@ConfigurationProperties`
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
