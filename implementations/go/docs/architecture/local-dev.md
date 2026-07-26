# Local Development Environment (Go)

The principle follows the root [local-dev.md](../../../../docs/architecture/local-dev.md): bring up the DB with Docker Compose, and replace cloud services with LocalStack. This repository's Go example actually implements this pattern, including SES notifications and SQS Outbox publish/consume — `implementations/go/examples/docker-compose.yml` and `localstack/init-ses.sh`/`init-secrets.sh`/`init-sqs.sh` are the real artifacts. This document explains that actual setup.

---

## Actual setup — `implementations/go/examples/docker-compose.yml`

```yaml
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

  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # Inside the container network, connect by service name instead of localhost.
      DATABASE_URL: postgres://dev:dev@database:5432/app?sslmode=disable
      AWS_ENDPOINT_URL: http://localstack:4566
      SQS_DOMAIN_EVENT_QUEUE_URL: http://localstack:4566/000000000000/domain-events
    depends_on:
      database:
        condition: service_healthy
      localstack:
        condition: service_healthy
    profiles:
      - app

volumes:
  db-data:
```

Compared against the root principle:
- **The LocalStack image version is pinned** — `localstack/localstack:3.0` (not `:latest`). This directly reflects the reason the root document states: "the `latest` tag can change behavior without notice."
- **The healthcheck calls a real service API** — `awslocal ses list-identities` (this filters out the case where the LocalStack process is up but SES subsystem initialization isn't finished yet). `database` is handled the same way with `pg_isready`.
- **The `app` service is separated with `profiles: [app]`.** The default run (`docker compose up -d`) brings up only the infrastructure (database/localstack); to also run the app as a container, use `docker compose --profile app up -d --build`. The `app` service must connect by the `database`/`localstack` service names within the container network, so `DATABASE_URL`/`AWS_ENDPOINT_URL` are overridden via `environment:` (which takes precedence over `env_file`).

---

## LocalStack initialization script — `localstack/init-ses.sh`

```sh
#!/bin/sh
set -e

awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

The `docker-compose.yml`'s `volumes: ['./localstack:/etc/localstack/init/ready.d']` mounts this script so it runs automatically once the LocalStack container finishes starting. As the root document emphasizes, **SES refuses to send at all unless the sender email is verified** — LocalStack's SES emulator reproduces this same behavior, so without this verification script, every email-sending test in `test/notification_e2e_test.go` fails.

When adding a new AWS service, follow the same pattern: add it to the `SERVICES` list in `docker-compose.yml`, and create a new `localstack/init-<service>.sh` script. Secrets Manager was added following this pattern (`SERVICES: ses,secretsmanager,sqs`, with `localstack/init-secrets.sh` creating the `app/jwt` secret — see [secret-manager.md](secret-manager.md)). SQS follows the same pattern too — `localstack/init-sqs.sh`:

```sh
#!/bin/sh
set -e

# The shared Domain/Integration Event queue that OutboxPoller publishes to and
# OutboxConsumer consumes from. The DLQ is created first, then wired to the main
# queue via RedrivePolicy — a message that still fails after exceeding
# maxReceiveCount (3) is quarantined into the DLQ (the DLQ convention from
# scheduling.md). The same queue name and parameters as the nestjs implementation
# are used to keep cross-language consistency.
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

The queue URL (`http://localhost:4566/000000000000/domain-events`) is passed to the app via the `SQS_DOMAIN_EVENT_QUEUE_URL` environment variable (`LoadSQSConfig()` in `internal/config/sqs.go`). The Poller/Consumer from [domain-events.md](domain-events.md) consume this queue.

---

## Running the app — branching to the LocalStack endpoint via environment variables

How the Go code branches between real AWS and LocalStack based on the presence of the `AWS_ENDPOINT_URL` environment variable is implemented in `internal/infrastructure/notification/ses_client.go`:

```go
// internal/infrastructure/notification/ses_client.go
func NewSESClient() *ses.Client {
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	if accessKeyID == "" {
		accessKeyID = "test"       // LocalStack doesn't check the credential value — any string is accepted
	}
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if secretAccessKey == "" {
		secretAccessKey = "test"
	}

	options := ses.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint  // LocalStack: http://localhost:4566
	}
	return ses.New(options)
}
```

The comment states clearly why **explicit static credentials are always used** instead of the default credential chain (IMDS, etc.) — to avoid the SDK's default chain waiting on a response and slowing things down in a sandbox/CI environment with no credentials. This matches the root principle exactly: "in local/test environments, use fixed values (`test`/`test`) to avoid IMDS latency." `NewSQSClient()` in `internal/infrastructure/outbox/sqs_client.go` has exactly the same setup.

Environment variables needed to run locally:

```env
DATABASE_URL=postgres://dev:dev@localhost:5432/app?sslmode=disable
AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
JWT_SECRET=local-dev-secret
APP_ENV=development
```

There's `.env.example` (committed) and `.env.development` (included in `.gitignore`, created by copying `.env.example`). The Go standard library doesn't automatically read `.env` files, so load it into the shell — e.g. `export $(cat .env.development | xargs) && go run ./cmd/server` — or use a tool like direnv.

---

## How to run it

```bash
# 1. Start infrastructure (Postgres + LocalStack)
cd implementations/go/examples
docker compose up -d

# 2. Run migrations (no separate migration tool currently — apply directly via psql, or the E2E tests run them automatically)
psql "$DATABASE_URL" -f migrations/0001_init.sql
psql "$DATABASE_URL" -f migrations/0002_add_email_and_sent_emails.sql
psql "$DATABASE_URL" -f migrations/0003_add_outbox.sql

# 3. Run the app (directly on the host)
export $(cat .env.development | xargs) && go run ./cmd/server

# --- or ---

# Start all containers (including the app)
docker compose --profile app up -d --build

# check logs
docker compose logs -f localstack

# tear down
docker compose down          # keeps data
docker compose down -v       # also removes data
```

The E2E tests (`test/account_e2e_test.go`) don't depend on this `docker-compose.yml` — testcontainers-go starts its own fresh Postgres/LocalStack (`ses,sqs`) containers on every test run and applies migrations itself (see [testing.md](testing.md)). `localstack/init-sqs.sh` is only for the manual local run that `docker-compose.yml` mounts, and it isn't mounted into the testcontainers-go container, so `setupDomainEventQueue` in `test/account_e2e_test.go` calls the SQS API (`CreateQueue`/`GetQueueAttributes`) directly to build the same queue+DLQ state. `docker-compose.yml` is purely for **manual local development** (calling the API via `go run` + curl/Postman).

---

### Related documents

- [config.md](config.md) — environment variable validation, useful when splitting `.env` files
- [secret-manager.md](secret-manager.md) — the local replacement for Secrets Manager (LocalStack `secretsmanager`)
- [domain-events.md](domain-events.md) — the actual Outbox → SQS (Poller) / SQS → Handler (Consumer) code
- [container.md](container.md) — the Dockerfile for running the app itself as a container
- [testing.md](testing.md) — the difference from testcontainers-go-based E2E
