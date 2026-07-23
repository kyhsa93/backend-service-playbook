# Local Development Environment — Kotlin Spring Boot

> For the framework-agnostic principles, see [root local-dev.md](../../../../docs/architecture/local-dev.md).

## Actual setup — `examples/docker-compose.yml`

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

Both infrastructure services — Postgres + LocalStack (SES/Secrets Manager/SQS) — have a `healthcheck` configured, following the root's "every infrastructure service must have a healthcheck" principle as-is. Calling an actual service API, like `awslocal ses list-identities` (rather than just checking that the process is alive), also matches the root's recommendation.

---

## LocalStack SES initialization — `init-ses.sh`

```bash
# examples/localstack/init-ses.sh — actual code
#!/bin/sh
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

Mounted at `./localstack:/etc/localstack/init/ready.d`, it runs automatically once LocalStack finishes booting. The root document especially emphasizes that SES **requires sender email verification** — LocalStack's SES emulator rejects sends from an unverified sender the same way real SES does, so without this script, `NotificationServiceImpl.sendEmail()` fails locally. `AccountControllerE2ETest`'s `@BeforeAll verifySesSender()` repeats the same verification in the test environment via Testcontainers, for the same reason.

---

## LocalStack SQS initialization — `init-sqs.sh`

```bash
# examples/localstack/init-sqs.sh — actual code
#!/bin/sh
set -e
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

This is the shared Domain/Integration Event queue that `OutboxPoller` publishes to and `OutboxConsumer`
receives from. The DLQ is created first, then connected to the main queue via `RedrivePolicy` — a
message that still fails after retrying past `maxReceiveCount(3)` is isolated into the DLQ (see the DLQ
convention in [domain-events.md](domain-events.md), [scheduling.md](scheduling.md)).

---

## Refund fraud-risk scoring — the optional `fraud-risk-scorer` service

`fraud-risk-scorer` (the shared ML microservice behind
`payment/infrastructure/RefundFraudRiskScorerHttpImpl.kt` — see root `docs/architecture/domain-service.md`'s
second `RefundFraudRiskScorer` example) is built from `../../../services/fraud-risk-scorer` (Python +
FastAPI + scikit-learn) and sits under its own `profiles: [ml]`, so it's not brought up by a plain
`docker compose up -d` and isn't in `app`'s `depends_on` — the default `FRAUD_SCORER_MODE=native`
(`payment/infrastructure/RefundFraudRiskScorerNativeImpl.kt`, an in-process hand-rolled logistic
regression) needs no extra service at all. Bring it up alongside `app` once you've set
`FRAUD_SCORER_MODE=http`:

```bash
docker compose --profile app --profile ml up -d --build
```

It listens on port 8000 inside the Compose network (no remap needed — `app` itself binds host
`8080`, not `8000`), and `app`'s `FRAUD_SCORER_BASE_URL` is set to `http://fraud-risk-scorer:8000` to
reach it by service name.

## The `app` service and `.env.*` files

`docker-compose.yml` has an `app` service too, not just `database`/`localstack`, but it's excluded from the default startup via `profiles: [app]` — the default `docker compose up -d` only brings up the infrastructure (Postgres, LocalStack); to run the app itself as a container too, use `docker compose --profile app up -d --build`:

```yaml
# docker-compose.yml — actual code
  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # Inside the container network, connect via service name instead of localhost.
      SPRING_DATASOURCE_URL: jdbc:postgresql://database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
      SQS_DOMAIN_EVENT_QUEUE_URL: http://localstack:4566/000000000000/domain-events
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

`.env.example` (committed) and `.env.development` (in `.gitignore`, created by copying `.env.example`) also already exist — they hold `SPRING_DATASOURCE_URL`/`AWS_REGION`/`AWS_ENDPOINT_URL`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`SES_SENDER_EMAIL`/`JWT_SECRET`. Since the Gradle/Kotlin build is heavy (compilation + daemon), the default workflow is to develop on the host with `./gradlew bootRun` + IDE incremental compilation/hot reload (Spring DevTools); the `app` service is used when you want to verify against the exact same container as the deployment environment.

---

## How to run it

```bash
cd implementations/kotlin-springboot/examples

# 1. Start infrastructure (Postgres + LocalStack)
docker compose up -d

# 2. Run the app — directly on the host (Gradle Wrapper)
./gradlew bootRun

# Check logs
docker compose logs -f localstack

# Shut down
docker compose down

# Shut down + delete volumes (reset DB data)
docker compose down -v
```

---

## App environment variables — `application.yml` + environment variable overrides

The current `examples/src/main/resources/application.yml` is minimal, as shown below.

```yaml
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

`NotificationServiceImpl`/`SesConfig` read environment variables via individual `@Value` injections like `@Value("\${SES_SENDER_EMAIL:...}")`, `@Value("\${AWS_REGION:us-east-1}")`. Environment variables needed when running `./gradlew bootRun` locally:

```bash
# .env.development (inject via an IDE Run Configuration or export when running directly)
AWS_ENDPOINT_URL=http://localhost:4566
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev
```

Spring Boot **automatically relaxed-binds** an uppercase-snake-case environment variable like `SPRING_DATASOURCE_URL` to the `spring.datasource.url` property — no separate mapping code is needed, unlike Node/NestJS's `ConfigModule`. See [config.md](config.md) for how to bundle these values type-safely into per-concern `@ConfigurationProperties` data classes.

Branching to LocalStack if `AWS_ENDPOINT_URL` is set, or to real AWS if it's empty, is implemented by `SesConfig.sesClient()` — see [config.md](config.md), [secret-manager.md](secret-manager.md).

---

## Principles

- **Every infrastructure service gets a healthcheck based on an actual service API**: `pg_isready`, `awslocal ses list-identities`.
- **LocalStack initialization scripts are committed**: `localstack/init-ses.sh`/`init-secrets.sh`/`init-sqs.sh` — every developer reproduces the same environment.
- **The LocalStack image version is pinned**: `localstack/localstack:3.0` — never use the `latest` tag.
- **SES requires sender verification**: the initialization script must run `verify-email-identity`.
- **Running the app directly on the host is the default**: given the nature of a Gradle/Kotlin build, IDE incremental compilation fits the development loop better than rebuilding a container.

### Related documents

- [config.md](config.md) — environment variable binding, `@ConfigurationProperties`
- [secret-manager.md](secret-manager.md) — the local substitute for Secrets Manager
- [container.md](container.md) — the app's own container image
