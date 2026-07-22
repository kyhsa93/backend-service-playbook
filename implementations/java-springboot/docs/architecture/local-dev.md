# Local Development Environment (Spring Boot)

> For the framework-agnostic principles, see the root [local-dev.md](../../../../docs/architecture/local-dev.md).

## Actual example — Postgres + LocalStack (SES/SQS)

`implementations/java-springboot/examples/docker-compose.yml` genuinely follows the root principle:

```yaml
# examples/docker-compose.yml — actual code
services:
  database:
    image: postgres:16-alpine
    ports: ['5432:5432']
    environment:
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
      POSTGRES_DB: app
    volumes: ['db-data:/var/lib/postgresql/data']
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U dev -d app']
      interval: 5s
      timeout: 3s
      retries: 5

  localstack:
    image: localstack/localstack:3.0
    ports: ['4566:4566']
    environment:
      SERVICES: ses,secretsmanager,sqs
      DEFAULT_REGION: us-east-1
    volumes: ['./localstack:/etc/localstack/init/ready.d']
    healthcheck:
      test: ['CMD-SHELL', 'awslocal ses list-identities']
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  db-data:
```

- **Pinned versions**: `postgres:16-alpine`, `localstack/localstack:3.0` — follows the root's "pin the LocalStack image version" principle.
- **Healthchecks call the real service API**: `pg_isready` (confirms Postgres connectivity), `awslocal ses list-identities` (confirms SES service initialization has finished) — this accurately detects the state where the container process is up but the internal service isn't ready yet.
- **`SERVICES: ses,secretsmanager,sqs` enables only what's needed**: this saves LocalStack startup time and memory. `sqs` is for the domain-events queue used by `OutboxPoller`/`OutboxConsumer` (see [domain-events.md](domain-events.md)).

---

## LocalStack init scripts — SES sender verification + SQS queue creation

```bash
#!/bin/sh
# examples/localstack/init-ses.sh — actual code
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

This is mounted at `./localstack:/etc/localstack/init/ready.d` and runs automatically once LocalStack finishes starting. **A project using SES must verify its sender email** — LocalStack's SES emulator rejects send requests from an unverified sender, just like real SES. `NotificationE2ETest` separately calls `verifyEmailIdentity` once more in `@BeforeAll` because Testcontainers spins up a fresh LocalStack container for every test run, so `docker-compose.yml`'s init script never gets applied to it (tests don't use the compose file) — the local-development compose setup and the test-time Testcontainers setup are independent of each other and each must handle its own initialization.

`init-sqs.sh` is also mounted in the same `ready.d` directory, creating the `domain-events` queue used by `OutboxPoller`/`OutboxConsumer` and its DLQ (`domain-events-dlq`):

```bash
#!/bin/sh
# examples/localstack/init-sqs.sh — actual code
set -e
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

The DLQ is created first, then attached to the main queue via `RedrivePolicy` — a message that still fails after exceeding `maxReceiveCount=3` retries gets isolated into the DLQ (the convention from [scheduling.md — DLQ monitoring](scheduling.md#dlq-monitoring)). E2E tests (`CardControllerE2ETest`, etc.) use a Testcontainers `LocalStackContainer`, which doesn't mount local init scripts, so `support/SqsTestQueue.java` reproduces the same configuration via SDK calls.

---

## The `app` service and `.env.*` files

`docker-compose.yml` has an `app` service in addition to `database`/`localstack`, excluded from the default startup via `profiles: [app]` — a plain `docker compose up -d` only brings up infrastructure (DB, LocalStack), and running the app itself as a container requires `docker compose --profile app up -d --build`:

```yaml
# docker-compose.yml — actual code (excerpt)
  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # Inside the container network, connect via service name instead of localhost.
      SPRING_DATASOURCE_URL: jdbc:postgresql://database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

Since `environment:` takes precedence over `env_file:`, the same `.env.development` used for running directly on the host is reused as-is, while overriding only the two values needed specifically inside the container network (`SPRING_DATASOURCE_URL`, `AWS_ENDPOINT_URL`).

Both `.env.example` (committed) and `.env.development` (in `.gitignore`, created by copying `.env.example`) already exist:

```env
# .env.example — actual code (used when running the app directly on the host via ./gradlew bootRun)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

JWT_SECRET=local-dev-secret-local-dev-secret
```

Since Spring Boot doesn't automatically read `.env` files (there's no standard mechanism equivalent to Node's `dotenv`), you load it into the shell yourself — e.g. `export $(cat .env.development | xargs) && ./gradlew bootRun` — or use something like direnv. Since `application.yml` embeds defaults in the form `${AWS_ACCESS_KEY_ID:test}`, local startup is possible even without `.env.development` — `.env.development` is used when you want to manage those defaults explicitly.

---

## LocalStack integration from the AWS SDK

```java
// account/infrastructure/notification/SesConfig.java — actual code
@Bean
public SesClient sesClient(
        @Value("${aws.region:us-east-1}") String region,
        @Value("${aws.endpoint-url:}") String endpointUrl,
        @Value("${aws.access-key-id:test}") String accessKeyId,
        @Value("${aws.secret-access-key:test}") String secretAccessKey
) {
    SesClientBuilder builder = SesClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    if (endpointUrl != null && !endpointUrl.isBlank()) {
        builder.endpointOverride(URI.create(endpointUrl));
    }
    return builder.build();
}
```

If `aws.endpoint-url` is set, it connects to LocalStack; if blank, it connects to the real AWS endpoint — this matches the root's "branch the endpoint via an environment variable" principle exactly. Always using `StaticCredentialsProvider` explicitly also matches the root's recommendation that "local/test environments should use fixed credentials" — the SDK's default credential-resolution chain (IMDS, etc.) has significant delay before it fails in a container/sandbox environment.

`outbox/SqsConfig.java`'s `sqsClient()` bean follows the same configuration (the same `AwsProperties`, the same endpoint branch) — `OutboxPoller`/`OutboxConsumer` share this single `SqsClient` (see [domain-events.md](domain-events.md)).

---

## How to run

```bash
# 1. Start infrastructure (Postgres + LocalStack)
cd implementations/java-springboot/examples
docker compose up -d

# 2. Run the app (directly on the host)
./gradlew bootRun

# Check logs
docker compose logs -f localstack

# Shut down
docker compose down

# Shut down + delete data
docker compose down -v
```

---

## Principles

- **Never connect directly to external services during local development**: use Docker Compose for the DB, LocalStack for SES/SQS.
- **Configure a healthcheck for every infrastructure service**: `pg_isready`, `awslocal ses list-identities`.
- **Commit the init scripts**: `localstack/init-ses.sh`, `localstack/init-sqs.sh`.
- **Pin image versions**: `postgres:16-alpine`, `localstack/localstack:3.0`.
- **Separate the app service via profiles**: the `app` service is excluded from the default startup via `profiles: [app]`.

---

### Related documents

- [config.md](config.md) — environment variable validation, splitting config files by concern
- [secret-manager.md](secret-manager.md) — the LocalStack Secrets Manager substitute
- [container.md](container.md) — the app's own container image, needed when adding the `app` service
