# Secret Management (Spring Boot / AWS Secrets Manager)

> For the framework-agnostic principles, see the root [secret-manager.md](../../../../docs/architecture/secret-manager.md). This is the implementation detail of the "sensitive values — environment variables vs. Secrets Manager" principle in [config.md](config.md).

## Current state — `SesConfig` still uses only environment variables

`common/service/SecretService` (interface) + `common/infrastructure/SecretServiceImpl` (an AWS Secrets Manager implementation + TTL cache) are implemented — the "`SecretService` — abstracted as a Technical Service" section below shows the actual code. `common/config/SecretsEnvironmentPostProcessor` also injects Secrets Manager values into the `Environment` early at startup (see below).

However, `account/infrastructure/notification/SesConfig.java` still receives AWS credentials only from `AwsProperties` (environment-variable-based, see [config.md](config.md)), without going through `SecretService`:

```java
// SesConfig.java — actual code
@Bean
public SesClient sesClient() {
    SesClientBuilder builder = SesClient.builder()
            .region(Region.of(awsProperties.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())));
    /* ... */
}
```

Since SES credentials are typically handled via IAM policy (SES is closer to a region/endpoint setting than a "secret"), this repository hasn't switched `SesConfig` to go through `SecretService` — `SecretService` is currently used only by `SecretsEnvironmentPostProcessor` to look up the JWT secret (see below). If you want to manage several values bundled as one secret (e.g. all DB connection info), extend the "using a JSON-shaped secret" pattern below.

---

## `SecretService` — abstracted as a Technical Service

The Technical Service pattern shown by `account/application/service/NotificationService` (interface) + `account/infrastructure/notification/NotificationServiceImpl` (implementation) is applied identically to secret lookup.

```java
// common/service/SecretService.java — actual code
public interface SecretService {
    String getSecret(String secretId);
}
```

```java
// common/infrastructure/SecretServiceImpl.java — actual code, AWS Secrets Manager implementation + TTL cache
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

- **A TTL cache via `ConcurrentHashMap`**: since Spring manages this bean as a singleton, multiple request threads can call `getSecret()` concurrently — a `ConcurrentHashMap` rather than a plain `HashMap` is needed for thread safety. Switching to a dedicated cache library like Caffeine (`com.github.ben-manes.caffeine:caffeine`) would allow finer control over the expiration policy (size-based eviction, etc.).
- **Reuses `AwsProperties` as-is**: rather than individual `@Value`s, `region`/`endpointUrl` lookups are handled by injecting the `AwsProperties` bean (`@ConfigurationProperties`) defined in [config.md](config.md) via the constructor — the same "LocalStack if endpoint-url is present, real AWS if not" pattern that `SesConfig` uses (see [local-dev.md](local-dev.md)).
- **One call site so far**: `SecretsEnvironmentPostProcessor` below builds its own `SecretsManagerClient` directly (it runs before the `ApplicationContext` — and therefore DI — exists, so it can't inject this bean) to resolve the JWT secret eagerly at startup. `payment/infrastructure/RefundReasonClassifierImpl` (the `RefundReasonClassifier` Technical Service — see [domain-service.md](domain-service.md)) does **not** use `SecretService`: it calls a self-hosted Ollama instance, and a base URL isn't a secret — see `config/RefundClassifierProperties.java` (a plain `@ConfigurationProperties` value, no Secrets Manager lookup).

---

## Using a JSON-shaped secret

Several values are stored as JSON in a single secret and accessed by key — logically grouped values (e.g. all DB connection info) are consolidated into one secret to reduce the number of API calls.

```java
// A hypothetical example — the only secret this repository actually has is the single app/jwt one from
// "Connecting with @ConfigurationProperties" below
// secretId: "account-service/database"
// value: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

ObjectMapper objectMapper = new ObjectMapper();
JsonNode dbSecret = objectMapper.readTree(secretService.getSecret("account-service/database"));
String password = dbSecret.get("password").asText();
```

---

## Connecting with `@ConfigurationProperties` — looked up only once at startup

To integrate the Secrets Manager lookup value into the `@ConfigurationProperties` + `@Validated` system described by [config.md](config.md), Spring's `EnvironmentPostProcessor` is used to inject Secrets Manager values into the `Environment` before `ApplicationContext` is prepared. The JWT signing key (`app/jwt`) is the only secret resolved this way — DB connection info hasn't been moved to Secrets Manager (Postgres is configured only via `SPRING_DATASOURCE_*` environment variables, see [local-dev.md](local-dev.md)), and the refund classifier's Ollama base URL isn't a secret at all (see [domain-service.md](domain-service.md) → "Technical Service — RefundReasonClassifier").

```java
// common/config/SecretsEnvironmentPostProcessor.java — actual code
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;   // skip for local/test

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
# META-INF/spring.factories — actual code, registration
org.springframework.boot.env.EnvironmentPostProcessor=com.example.accountservice.common.config.SecretsEnvironmentPostProcessor
```

`EnvironmentPostProcessor` runs at the earliest possible point, before `ApplicationContext` is fully prepared, so `SecurityConfig`'s `@Value("${jwt.secret}")` binding (see [authentication.md](authentication.md)) uses this value as-is afterward — it's branched to **run only in the production profile**, so it never affects local/test startup speed. On lookup failure it throws `IllegalStateException`, aborting application startup entirely — refusing to start without a JWT secret is the correct fail-fast behavior.

**A cross-language difference — the gating mechanism itself differs**: this repository gates via a Spring **profile** (`Profiles.of("prod")`), not an environment variable — `logback-spring.xml` (see [observability.md](observability.md)) is also unified under the same `prod`/`!prod` profiles. kotlin-springboot uses the same mechanism (`Profiles.of("prod")`). nestjs (`NODE_ENV !== 'production'`), go (`APP_ENV != "production"`), and fastapi (`APP_ENV == "production"`) gate via an environment-variable value, and of these, fastapi has the opposite polarity from the other two. Never assume the name and polarity map directly across other languages' documentation.

If you want to move DB connection info (`spring.datasource.username`/`password`) to Secrets Manager, extend this class's pattern directly — just change `secretId` to `account-service/database` and add `spring.datasource.*` keys to `props`. This is not currently adopted.

---

## A non-consumer, by design — `RefundReasonClassifierImpl`

`payment/infrastructure/RefundReasonClassifierImpl.java` (the `RefundReasonClassifier` Technical Service — see [domain-service.md](domain-service.md)) does not inject `SecretService` at all, in production or otherwise. It calls a self-hosted Ollama instance (`docker-compose.yml`'s `ollama`/`ollama-init` services) over plain HTTP, and the only configuration it needs — the base URL and model name — is a plain, non-sensitive value bound via `@ConfigurationProperties` (`config/RefundClassifierProperties.java`), exactly like `AwsProperties.region` or `SesProperties.senderEmail`:

```java
// config/RefundClassifierProperties.java — actual code
@ConfigurationProperties(prefix = "refund-classifier")
@Validated
public record RefundClassifierProperties(@NotBlank String ollamaBaseUrl, @NotBlank String model) {}
```

An earlier iteration of this Technical Service called the Claude API and needed an API key, which — being a genuine secret — was looked up from Secrets Manager in production via a lazily-injected `SecretService` (the same "gate on `Profiles.of("prod")`, resolve on first use inside a DI-managed `@Component`" pattern `SecretsEnvironmentPostProcessor` uses eagerly for the JWT secret above). Swapping the backend to a self-hosted model removed that secret entirely — there's nothing left in this Technical Service for `SecretService`/Secrets Manager to protect. This is one concrete illustration of why `RefundReasonClassifier`'s interface is defined in the shape the Domain Service needs, not around a specific vendor's API: the backend swap required no change above the Infrastructure layer, including whether a secret is involved at all.

---

## Local development — LocalStack

```bash
# examples/localstack/init-secrets.sh — actual code
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret-local-dev-secret"}'
```

```yaml
# docker-compose.yml — actual code
localstack:
  environment:
    SERVICES: ses,secretsmanager
```

`docker-compose.yml`'s `SERVICES` includes `secretsmanager`, and `init-secrets.sh` automatically creates the `app/jwt` secret when the LocalStack container starts — locally, the exact same Secrets Manager path as production is exercised (though `SecretsEnvironmentPostProcessor` only actually performs the lookup when `SPRING_PROFILES_ACTIVE=prod`).

---

## Principles

- **Never permanently rely on an environment-variable default (`test`/`test`) for a sensitive value**: a production environment looks it up from Secrets Manager (`app/jwt`).
- **A TTL cache is required**: since Secrets Manager is billed per call, avoid looking it up on every request — `SecretServiceImpl` implements this.
- **Abstract it behind a `SecretService` interface**: follows the same Technical Service pattern as `NotificationService`.
- **Store logically grouped values as JSON in a single secret.**
- **Inject early at startup via an `EnvironmentPostProcessor`**: the value must be ready before `@ConfigurationProperties`/`@Value` binding occurs — `SecretsEnvironmentPostProcessor` handles this.

---

### Related documents

- [config.md](config.md) — criteria for choosing environment variables vs. Secrets Manager, `@ConfigurationProperties`
- [local-dev.md](local-dev.md) — the LocalStack-based local development environment
