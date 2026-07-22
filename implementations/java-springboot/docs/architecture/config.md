# Environment Configuration Management (Spring Boot)

> For the framework-agnostic principles, see the root [config.md](../../../../docs/architecture/config.md).

## State of the current example

The full `examples/src/main/resources/application.yml`:

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

refund-classifier:
  ollama-base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
  model: ${REFUND_CLASSIFIER_MODEL:qwen2.5:1.5b}
```

(For `ddl-auto`/migration status, see [persistence.md](persistence.md) — migrations are managed by Flyway.)

Startup-time validation is genuinely enforced via `config/AwsProperties.java`/`config/SesProperties.java` (`@ConfigurationProperties` + `@Validated`), and `application-prod.yml` removes the default AWS credential values in the production profile to force fail-fast — the actual code is shown as-is below.

- **`AwsProperties.accessKeyId`/`secretAccessKey` are also subject to `@NotBlank`**: like `region`, both fields carry Bean Validation annotations. The local default values (`test`/`test`) are non-blank, so they pass this validation without affecting local development convenience. `application-prod.yml` omitting the default for these placeholders (see below) and `@NotBlank` are a deliberate two-layer defense catching different failure conditions — if the environment variable itself is absent, a `PlaceholderResolutionException` is thrown (before property binding); if it's set to an empty string, `@NotBlank` catches it (during Bean Validation, after binding).
- **Splitting config files by concern has settled on the current 2-file structure (`application.yml` + `application-prod.yml`)**: given this repository's small configuration surface (AWS credentials, SES sender, JWT secret), finer splits like `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml` were judged unnecessary complexity and are not adopted — see the "Splitting config files by concern" section below.

---

## `@ConfigurationProperties` + `@Validated` — fail-fast validation

Rather than scattering individual `@Value`s across many classes, related settings are grouped into `@ConfigurationProperties` classes by concern and validated at startup with Bean Validation.

```java
// config/AwsProperties.java — actual code
@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsProperties(
        @NotBlank String region,
        String endpointUrl,             // local-only — left blank in production, blank is allowed
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey
) {}
```

```java
// config/SesProperties.java — actual code
@ConfigurationProperties(prefix = "ses")
@Validated
public record SesProperties(@NotBlank @Email String senderEmail) {}
```

```java
// AccountServiceApplication.java — actual code
@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

Declaring `@ConfigurationProperties` as a `record` (supported since Spring Boot 3.x) means it's injected as an immutable object, and Bean Validation annotations such as `@NotBlank`/`@Email` are validated **at application-context loading time**. If a value is blank, a `BindValidationException` is thrown, `ApplicationContext` initialization fails, and the process terminates immediately — the Spring equivalent of Node's `process.exit(1)` fail-fast. `region`/`senderEmail`/`accessKeyId`/`secretAccessKey` are all directly validated by this mechanism. `accessKeyId`/`secretAccessKey` additionally get fail-fast from omitting their defaults in `application-prod.yml` below — a deliberate two-layer defense that catches a missing environment variable and an empty-string value at two different layers.

```java
// account/infrastructure/notification/SesConfig.java — actual code, injected only in the Infrastructure layer
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

**Balancing local defaults with fail-fast:** having a LocalStack default value like `${AWS_ACCESS_KEY_ID:test}` in `application.yml` is reasonable for local development convenience on its own. The actual problem would be **always allowing the same default regardless of environment (prod vs. dev)** — this repository solves that by removing the default only in the production profile, via `application-prod.yml` below.

---

## Splitting config files by concern — settled on a 2-file structure

This repository currently has only two files, `application.yml` + `application-prod.yml` — below is the actual code:

```yaml
# application-prod.yml — actual code, production-only, forces environment variables with no default
aws:
  access-key-id: ${AWS_ACCESS_KEY_ID}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY}
```

In the production profile (`application-prod.yml`), the **default value is omitted**, as in `${AWS_ACCESS_KEY_ID}`. If Spring finds no environment variable corresponding to a placeholder, it throws a `PlaceholderResolutionException` and startup fails — this is Spring Boot's natural fail-fast mechanism, and together with `@NotBlank` on `AwsProperties.accessKeyId`/`secretAccessKey` (see above), it catches the two failure conditions of a missing environment variable and an empty-string value at two separate layers.

**A more finely split structure is not adopted**: splitting into `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml` based on `spring.config.import` was considered, but this repository's configuration surface (3 AWS credential fields, 1 SES sender field, 1 JWT secret field) doesn't reach a scale that warrants managing separate files — this was judged unnecessary complexity, and the 2-file structure of `application.yml` + `application-prod.yml` is settled on as the final structure. If the number of domains grows and configuration items genuinely increase (e.g. detailed DB connection-pool tuning, additional external integrations), this structure should be revisited at that point.

---

## Sensitive values — environment variables vs. Secrets Manager

| Item | Approach |
|------|------|
| General config (region, endpoint, timeout) | Environment variable → `@ConfigurationProperties` |
| Sensitive values (DB password, JWT secret, API keys) | AWS Secrets Manager → looked up at startup and injected into the config object |

See [secret-manager.md](secret-manager.md) for the detailed lookup/caching implementation. Whether `application-prod.yml` references an environment variable, and whether that environment variable's value itself was injected by an orchestrator from Secrets Manager, are separate concerns — the ECS Task Definition's `secrets` field or Kubernetes' `envFrom: secretRef` is what handles that injection.

---

## Configuration access pattern — Infrastructure layer only

`@ConfigurationProperties`/`@Value` injection happens only in the Infrastructure layer (`SesConfig`, `RepositoryImpl`, etc.). Application Services (`CreateAccountService`, etc.) and Domain (`Account`) never reference configuration values directly.

```java
// Correct — config is accessed only in Infrastructure
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final SesProperties sesProperties;   // Infrastructure layer
    ...
}

// Incorrect — an Application Service reads config directly
@Service
public class CreateAccountService {
    @Value("${aws.region}")   // forbidden — the Application layer must not depend on config
    private String region;
}
```

---

## Principles

- **Fail-fast**: validate at startup with `@ConfigurationProperties` + Bean Validation — `AwsProperties`/`SesProperties` implement this, and every field (`region`/`accessKeyId`/`secretAccessKey`/`senderEmail`) carries `@NotBlank`. On failure, `ApplicationContext` loading aborts and the process exits.
- **Splitting by concern has settled on a 2-file structure**: given this repository's configuration surface, `application.yml` + a production-profile override (`application-prod.yml`) is judged sufficient — no further `spring.config.import` splitting is adopted (see above).
- **The production profile omits defaults**: use `${VAR}` (no default) to force validation — `application-prod.yml` follows this pattern.
- **Sensitive values go through Secrets Manager**: see [secret-manager.md](secret-manager.md).
- **Configuration access is confined to the Infrastructure layer**: `@Value`/`@ConfigurationProperties` injection targets are limited to Infrastructure's `@Configuration`/`@Component` classes.

---

## Harness verification

`harness/src/rules/NoDirectEnvAccessOutsideConfig.java` (rule: `no-direct-env-access-outside-config`) fails the build if `domain/`·`application/` calls `System.getenv(...)` directly — environment-variable access must be wrapped in `@ConfigurationProperties` and only done in `config/` (the top-level shared package) or `infrastructure/`.

---

### Related documents

- [container.md](container.md) — container environment-variable injection
- [secret-manager.md](secret-manager.md) — Secrets Manager lookup/caching
- [local-dev.md](local-dev.md) — local development profile configuration
