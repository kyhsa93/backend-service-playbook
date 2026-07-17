# 환경 설정 관리 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [config.md](../../../../docs/architecture/config.md) 참고.

## 현재 예제의 상태

`examples/src/main/resources/application.yml` 전체:

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

aws:
  region: ${AWS_REGION:us-east-1}
  endpoint-url: ${AWS_ENDPOINT_URL:}
  access-key-id: ${AWS_ACCESS_KEY_ID:test}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY:test}

ses:
  sender-email: ${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}

jwt:
  secret: ${JWT_SECRET:dev-secret-dev-secret-dev-secret}
```

(`ddl-auto`/마이그레이션 상태는 [persistence.md](persistence.md) 참고 — 마이그레이션은 Flyway로 관리된다.)

`config/AwsProperties.java`/`config/SesProperties.java`(`@ConfigurationProperties` + `@Validated`)를 통해 기동 시점 검증이 실제로 동작하고, `application-prod.yml`이 운영 프로필에서 AWS 자격증명 기본값을 제거해 fail-fast를 강제한다 — 아래에서 실제 코드를 그대로 보여준다. 다만 두 가지는 아직 남은 gap이다:

- **관심사별 설정 파일 분리는 부분적이다**: `application-prod.yml` 하나만 실제로 존재하고, 아래 "관심사별 설정 파일 분리" 절이 제안하는 `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml`처럼 세분화된 `spring.config.import` 구성은 아직 도입되지 않았다.
- **`AwsProperties.accessKeyId`/`secretAccessKey`는 Bean Validation 대상이 아니다**: 아래 실제 코드가 보여주듯 `@NotBlank`는 `region`에만 붙어 있다. 로컬 기본값(`test`/`test`)이 운영에서도 조용히 통과하는 것을 막는 역할은 `AwsProperties`의 Bean Validation이 아니라 `application-prod.yml`이 해당 플레이스홀더에 기본값을 두지 않는 것(아래 참고)이 담당한다 — 두 메커니즘이 같은 목표를 다른 층에서 나눠 맡고 있다는 점에 주의한다.

---

## `@ConfigurationProperties` + `@Validated` — Fail-fast 검증

개별 `@Value`를 여러 클래스에 흩어놓는 대신, 관심사별 `@ConfigurationProperties` 클래스로 묶고 Bean Validation으로 기동 시 검증한다.

```java
// config/AwsProperties.java — 실제 코드
@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsProperties(
        @NotBlank String region,
        String endpointUrl,             // 로컬 전용 — 운영에서는 비워둠
        String accessKeyId,             // Bean Validation 대상 아님 — 아래 "현재 예제의 상태" 참고
        String secretAccessKey          // 마찬가지
) {}
```

```java
// config/SesProperties.java — 실제 코드
@ConfigurationProperties(prefix = "ses")
@Validated
public record SesProperties(@NotBlank @Email String senderEmail) {}
```

```java
// AccountServiceApplication.java — 실제 코드
@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

`@ConfigurationProperties`를 `record`로 선언하면(Spring Boot 3.x 지원) 불변 객체로 주입되고, `@NotBlank`/`@Email` 등 Bean Validation 애노테이션이 **애플리케이션 컨텍스트 로딩 시점**에 검증된다. 값이 비어 있으면 `BindValidationException`이 발생하며 `ApplicationContext` 초기화가 실패하고 프로세스가 즉시 종료된다 — Node의 `process.exit(1)`에 해당하는 Spring식 fail-fast다. `region`/`senderEmail`은 이 메커니즘으로 직접 검증되고, `accessKeyId`/`secretAccessKey`는 아래 `application-prod.yml`의 기본값 생략으로 fail-fast를 얻는다(신규로 `@NotBlank`를 추가해 검증을 이중화하는 것도 가능하다).

```java
// account/infrastructure/notification/SesConfig.java — 실제 코드, Infrastructure 레이어에서만 주입받는다
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

**로컬 기본값과 fail-fast의 균형:** `application.yml`처럼 `${AWS_ACCESS_KEY_ID:test}`로 LocalStack용 기본값을 두는 것 자체는 로컬 개발 편의상 합리적이다. **운영/개발 환경을 구분하지 않고 항상 같은 기본값을 허용**하는 것이 문제인데, 이 저장소는 아래 `application-prod.yml`로 운영 프로필에서만 기본값을 제거해 이를 해결한다.

---

## 관심사별 설정 파일 분리 — 부분 적용 (운영 프로필 오버라이드만 실재)

이 저장소는 현재 `application.yml` + `application-prod.yml` 두 파일만 갖는다 — 아래는 실제 코드다:

```yaml
# application-prod.yml — 실제 코드, 운영 전용, 기본값 없이 환경 변수 강제
aws:
  access-key-id: ${AWS_ACCESS_KEY_ID}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY}
```

운영 프로필(`application-prod.yml`)에서는 `${AWS_ACCESS_KEY_ID}`처럼 **기본값을 생략**한다. Spring은 플레이스홀더에 대응하는 환경 변수가 없으면 `PlaceholderResolutionException`을 던지며 기동을 실패시킨다 — 이것이 Spring Boot의 자연스러운 fail-fast 메커니즘이며, `AwsProperties.accessKeyId`/`secretAccessKey`에 Bean Validation이 없어도 이 경로로 fail-fast가 성립하는 이유다.

**아직 도입되지 않은 것**: `spring.config.import` 기반의 세분화된 관심사별 분리(`application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml`)는 여전히 제안 단계다 — 규모가 커지면 아래 구조로 확장한다.

```
src/main/resources/
  application.yml              # 공통 (spring.application.name 등) + 하위 파일 import
  application-database.yml     # DB 연결 (제안, 아직 없음)
  application-aws.yml          # AWS(SES/S3/Secrets Manager) 설정 (제안, 아직 없음)
  application-jwt.yml          # JWT 설정 (제안, 아직 없음 — authentication.md 참고)
  application-local.yml        # 로컬 전용 오버라이드 (제안, 아직 없음)
  application-prod.yml         # 운영 전용 오버라이드 (실제 존재, 위 참고)
```

```yaml
# application.yml — 세분화된 spring.config.import 도입 시 (제안)
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

- **Fail-fast**: `@ConfigurationProperties` + Bean Validation으로 기동 시 검증한다 — `AwsProperties`/`SesProperties`가 이를 구현한다. 실패 시 `ApplicationContext` 로딩이 중단되고 프로세스가 종료된다.
- **관심사별 분리**: `spring.config.import`로 설정 파일을 나눈다 — 운영 프로필 오버라이드(`application-prod.yml`)만 실재하고, 나머지 세분화는 아직 제안 단계(위 참고).
- **운영 프로필은 기본값을 생략**한다: `${VAR}` (기본값 없음)로 강제 검증 — `application-prod.yml`이 이 패턴을 따른다.
- **민감값은 Secrets Manager**: [secret-manager.md](secret-manager.md) 참고.
- **설정 접근은 Infrastructure 레이어**: `@Value`/`@ConfigurationProperties` 주입 대상은 Infrastructure의 `@Configuration`/`@Component` 클래스로 한정한다.

---

### 관련 문서

- [container.md](container.md) — 컨테이너 환경 변수 주입
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱
- [local-dev.md](local-dev.md) — 로컬 개발 프로필 구성
