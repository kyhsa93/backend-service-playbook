# 환경 설정 관리 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [config.md](../../../../docs/architecture/config.md) 참고.

## 현재 예제의 상태 — 알려진 gap

`examples/src/main/resources/application.yml` 전체:

```yaml
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false

aws:
  region: ${AWS_REGION:us-east-1}
  endpoint-url: ${AWS_ENDPOINT_URL:}
  access-key-id: ${AWS_ACCESS_KEY_ID:test}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY:test}

ses:
  sender-email: ${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}
```

- 모든 설정이 단일 파일에 있다 (관심사별 분리 없음).
- `aws.access-key-id`/`aws.secret-access-key`가 `test`/`test`를 기본값으로 가져, 운영 환경 변수 주입을 잊어도 기동은 되어버린다 — fail-fast가 아니라 조용한 오설정이다.
- 기동 시점의 필수값 검증이 전혀 없다. `SesConfig`/`NotificationServiceImpl`이 `@Value("${...:기본값}")`로 값을 개별적으로 주입받을 뿐, 앱 전체 차원의 "필수 설정 누락 시 즉시 종료" 로직이 없다.

---

## `@ConfigurationProperties` + `@Validated` — Fail-fast 검증

개별 `@Value`를 여러 클래스에 흩어놓는 대신, 관심사별 `@ConfigurationProperties` 클래스로 묶고 Bean Validation으로 기동 시 검증한다.

```java
// config/AwsProperties.java
@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsProperties(
        @NotBlank String region,
        String endpointUrl,             // 로컬 전용 — 운영에서는 비워둠
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey
) {}
```

```java
// config/SesProperties.java
@ConfigurationProperties(prefix = "ses")
@Validated
public record SesProperties(@NotBlank @Email String senderEmail) {}
```

```java
// AccountServiceApplication.java
@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

`@ConfigurationProperties`를 `record`로 선언하면(Spring Boot 3.x 지원) 불변 객체로 주입되고, `@NotBlank`/`@Email` 등 Bean Validation 애노테이션이 **애플리케이션 컨텍스트 로딩 시점**에 검증된다. 값이 비어 있으면 `BindValidationException`이 발생하며 `ApplicationContext` 초기화가 실패하고 프로세스가 즉시 종료된다 — Node의 `process.exit(1)`에 해당하는 Spring식 fail-fast다.

```java
// 사용 — Infrastructure 레이어에서만 주입받는다
@Configuration
@RequiredArgsConstructor
public class SesConfig {

    private final AwsProperties awsProperties;

    @Bean
    public SesClient sesClient() {
        SesClientBuilder builder = SesClient.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())));
        if (awsProperties.endpointUrl() != null && !awsProperties.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl()));
        }
        return builder.build();
    }
}
```

**주의 — 로컬 기본값과 fail-fast의 균형:** 현재 예제처럼 `${AWS_ACCESS_KEY_ID:test}`로 LocalStack용 기본값을 두는 것 자체는 로컬 개발 편의상 합리적이다. 문제는 **운영/개발 환경을 구분하지 않고 항상 같은 기본값을 허용**하는 것이다. `@Profile`로 분기하여 운영 프로필에서는 기본값을 제거한다(아래 참고).

---

## 관심사별 설정 파일 분리

단일 `application.yml` 대신 관심사별로 나누고, `spring.config.import`로 조합한다.

```
src/main/resources/
  application.yml              # 공통 (spring.application.name 등) + 하위 파일 import
  application-database.yml     # DB 연결
  application-aws.yml          # AWS(SES/S3/Secrets Manager) 설정
  application-jwt.yml          # JWT 설정 (authentication.md 참고)
  application-local.yml        # 로컬 전용 오버라이드 (profile: local)
  application-prod.yml         # 운영 전용 오버라이드 (profile: prod, 기본값 없음)
```

```yaml
# application.yml
spring:
  application:
    name: account-service
  config:
    import:
      - application-database.yml
      - application-aws.yml
      - application-jwt.yml
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
```

```yaml
# application-prod.yml — 운영 전용, 기본값 없이 환경 변수 강제
aws:
  region: ${AWS_REGION}
  access-key-id: ${AWS_ACCESS_KEY_ID}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY}
```

운영 프로필(`application-prod.yml`)에서는 `${AWS_ACCESS_KEY_ID}`처럼 **기본값을 생략**한다. Spring은 플레이스홀더에 대응하는 환경 변수가 없으면 `PlaceholderResolutionException`을 던지며 기동을 실패시킨다 — 이것이 Spring Boot의 자연스러운 fail-fast 메커니즘이다.

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 방식 |
|------|------|
| 일반 설정 (리전, 엔드포인트, 타임아웃) | 환경 변수 → `@ConfigurationProperties` |
| 민감값 (DB 비밀번호, JWT secret, API 키) | AWS Secrets Manager → 기동 시 조회 후 설정 객체에 주입 |

상세 조회/캐싱 구현은 [secret-manager.md](secret-manager.md) 참고. `application-prod.yml`이 환경 변수를 참조하는 것과, 그 환경 변수 값 자체가 오케스트레이터가 Secrets Manager에서 주입한 것인지는 별개의 관심사다 — ECS Task Definition의 `secrets` 필드나 Kubernetes `envFrom: secretRef`가 이 주입을 담당한다.

---

## 설정 접근 패턴 — Infrastructure 레이어에서만

`@ConfigurationProperties`/`@Value` 주입은 Infrastructure 레이어(`SesConfig`, `RepositoryImpl` 등)에서만 한다. Application Service(`CreateAccountService` 등)와 Domain(`Account`)은 설정값을 직접 참조하지 않는다.

```java
// 올바른 방식 — Infrastructure에서만 설정 접근
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final SesProperties sesProperties;   // Infrastructure 레이어
    ...
}

// 잘못된 방식 — Application Service가 설정을 직접 읽음
@Service
public class CreateAccountService {
    @Value("${aws.region}")   // 금지 — Application 레이어는 설정 무의존
    private String region;
}
```

---

## 원칙

- **Fail-fast**: `@ConfigurationProperties` + Bean Validation으로 기동 시 검증한다. 실패 시 `ApplicationContext` 로딩이 중단되고 프로세스가 종료된다.
- **관심사별 분리**: `spring.config.import`로 설정 파일을 나눈다.
- **운영 프로필은 기본값을 생략**한다: `${VAR}` (기본값 없음)로 강제 검증.
- **민감값은 Secrets Manager**: [secret-manager.md](secret-manager.md) 참고.
- **설정 접근은 Infrastructure 레이어**: `@Value`/`@ConfigurationProperties` 주입 대상은 Infrastructure의 `@Configuration`/`@Component` 클래스로 한정한다.

---

### 관련 문서

- [container.md](container.md) — 컨테이너 환경 변수 주입
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱
- [local-dev.md](local-dev.md) — 로컬 개발 프로필 구성
