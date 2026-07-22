# Local Development Environment

> Framework-agnostic principles: [../../../../docs/architecture/local-dev.md](../../../../docs/architecture/local-dev.md)

This is implemented to match the root document's criteria — `examples/docker-compose.yml`, `examples/localstack/init-ses.sh`, `examples/localstack/init-secrets.sh`, and `examples/localstack/init-sqs.sh` follow the actual root principles (Docker Compose infrastructure + LocalStack + an app container profile).

## Current setup

```
implementations/fastapi/examples/
  docker-compose.yml         ← Postgres + LocalStack(SES, Secrets Manager, SQS) + Ollama + app(profiles: [app])
  .env.example                ← the committed template — copy this to create .env.development
  .gitignore                  ← excludes local-only values from commits via the .env* pattern
  localstack/
    init-ses.sh               ← script that verifies the SES sender email
    init-secrets.sh            ← creates the app/jwt secret in Secrets Manager
    init-sqs.sh                ← creates the domain-events queue + DLQ used by OutboxPoller/OutboxConsumer
```

```yaml
# docker-compose.yml — actual code
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

  ollama:
    image: ollama/ollama:latest
    ports: ['11434:11434']
    volumes: ['ollama-data:/root/.ollama']
    healthcheck:
      test: ['CMD-SHELL', 'ollama list || exit 1']
      interval: 5s
      timeout: 3s
      retries: 10

  # A one-shot init container — pulls the classifier model into the shared ollama-data volume
  # once, then exits. `app` depends on it completing (service_completed_successfully), the same
  # role LocalStack's init/ready.d scripts play for AWS resources.
  ollama-init:
    image: ollama/ollama:latest
    entrypoint: ['/bin/sh', '-c']
    command: ['ollama pull qwen2.5:1.5b']
    environment:
      OLLAMA_HOST: ollama:11434
    depends_on:
      ollama:
        condition: service_healthy

  app:
    build: .
    ports: ['8000:8000']
    env_file:
      - .env.development
    environment:
      # Inside the container network, connect via the service name instead of localhost.
      DATABASE_URL: postgresql+asyncpg://dev:dev@database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
      SQS_DOMAIN_EVENT_QUEUE_URL: http://localstack:4566/000000000000/domain-events
      SQS_TASK_QUEUE_URL: http://localstack:4566/000000000000/tasks.fifo
      OLLAMA_BASE_URL: http://ollama:11434
    depends_on:
      database:
        condition: service_healthy
      localstack:
        condition: service_healthy
      ollama-init:
        condition: service_completed_successfully
    profiles:
      - app

volumes:
  db-data:
  ollama-data:
```

`ollama` (the open-source LLM server, for `payment/infrastructure/refund_reason_classifier_impl.py`) serves the model over its native `/api/chat` HTTP endpoint; `ollama-init` (the one-shot pull container above) runs once and exits.

```bash
# localstack/init-ses.sh
#!/bin/sh
set -e

awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

```bash
# localstack/init-secrets.sh
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

```bash
# localstack/init-sqs.sh — creates the DLQ first, then links it to the main queue via RedrivePolicy
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

The DLQ + `maxReceiveCount=3` follow exactly the convention required by [scheduling.md — DLQ monitoring](../../../../docs/architecture/scheduling.md#monitoring-the-dlq) — the queue names and RedrivePolicy match the nestjs implementation's `localstack/init-sqs.sh` (cross-language consistency).

**Parts that already precisely follow the root principles:**
- Every infrastructure service has a `healthcheck` configured (`pg_isready`, `awslocal ses list-identities`).
- The LocalStack image version is pinned to `3.0` (not `latest`).
- The initialization scripts (`init-ses.sh`, `init-secrets.sh`, `init-sqs.sh`) are committed to the repository so every developer can reproduce the same environment.
- SES sender-email verification is handled ahead of time in the initialization script — because LocalStack's SES emulator rejects an unverified sender just like real SES does (see the comment in `test_notification_e2e.py`).
- The `app` service is separated via `profiles: [app]`, so the default `docker compose up` starts only the infrastructure, keeping the workflow of running the app directly on the host, while still allowing the app to be started as a container too when needed.
- The `app` service references the Dockerfile from `container.md` via `build: .`, and overrides environment variables so that, inside the container network, it connects via the service name (`database`, `localstack`) instead of `localhost`.
- `.gitignore` includes `.env.development` and `.env.docker`, so local-only values are never committed.

```
# .gitignore — actual code
__pycache__/
*.pyc
.pytest_cache/
.env.development
.env.docker
```

---

## `.env.example` — the committed template

```env
# .env.example — actual code
# Used when running the app directly locally with uvicorn main:app --reload.
# Copy this file to create .env.development — .env.development is never committed.
# uvicorn/FastAPI doesn't automatically read a .env file, so load it into the shell via
# direnv or similar, or run it like `export $(cat .env.development | xargs) && uvicorn main:app --reload`.

DATABASE_URL=postgresql+asyncpg://dev:dev@localhost:5432/app

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

JWT_SECRET=local-dev-secret
APP_ENV=development

OLLAMA_BASE_URL=http://localhost:11434
REFUND_CLASSIFIER_MODEL=qwen2.5:1.5b
```

With `APP_ENV=development` (or unset), `main.py`'s `lifespan` doesn't call Secrets Manager and uses the `JWT_SECRET` environment variable as-is — only when `APP_ENV=production` does it look up the `app/jwt` secret created by `localstack/init-secrets.sh` (see [secret-manager.md](secret-manager.md)).

---

## How to run it

```bash
# 1. Start infrastructure (database + localstack only — the app profile is excluded by default)
docker compose up -d

# 2. Run the app (directly on the host — hot reload during development)
cp .env.example .env.development   # once, the first time
export $(cat .env.development | xargs) && uvicorn main:app --reload --port 8000

# --- or ---

# Start every container (including the app)
docker compose --profile app up -d

# Check logs
docker compose logs -f app

# Shut down
docker compose --profile app down

# Shut down + delete data
docker compose --profile app down -v
```

---

## AWS SDK — LocalStack integration

`SesNotificationService` (`infrastructure/notification/notification_service.py`), `AwsSecretService` (`common/aws_secret_service.py`), and `OutboxPoller`/`OutboxConsumer` (`outbox/outbox_poller.py`/`outbox_consumer.py`) all share the same environment-variable-based endpoint branching via `AwsConfig.client_kwargs()` (`config/aws_config.py`).

```python
async with self._boto_session.client(
    "sqs",  # or "ses"/"secretsmanager" — only the service name differs, everything else is the same
    region_name=os.getenv("AWS_REGION", "us-east-1"),
    endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,   # LocalStack if set, real AWS otherwise
    aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
    aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
) as sqs_client:
    ...
```

The queue URL is read by `SqsConfig.domain_event_queue_url` (`config/sqs_config.py`) from the `SQS_DOMAIN_EVENT_QUEUE_URL` environment variable.

`test_notification_e2e.py`/`test_card_e2e.py`/`test_payment_e2e.py` verify this entire flow (SES send / SQS publish-receive → confirming the message in LocalStack) using `testcontainers.localstack.LocalStackContainer` — see the E2E testing section of [testing.md](testing.md).

---

## Principles

- **Never connect directly to an external service during local development**: the DB uses Docker Compose, AWS services use LocalStack.
- **Configure a healthcheck for every infrastructure service**.
- **Pin the LocalStack image version**: currently pinned to `3.0`.
- **Commit initialization scripts to the repository**: `localstack/init-*.sh`.
- **Separate the app service via profiles** — the `app` service is excluded from the default startup via `profiles: [app]`.
- **Never commit `.env*`**: included in `.gitignore`.

### Related documents

- [config.md](config.md) — environment-variable validation, `.env` file management
- [secret-manager.md](secret-manager.md) — the local replacement for Secrets Manager (LocalStack)
- [container.md](container.md) — the app's own container image (Dockerfile)
- [testing.md](testing.md) — testcontainers-based E2E testing
