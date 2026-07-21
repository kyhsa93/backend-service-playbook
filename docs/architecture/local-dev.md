# Local Development Environment

For local development, run external infrastructure (DB, cache, AWS services, etc.) via **Docker Compose**, and replace cloud services (S3, SQS, Secrets Manager, SES, etc.) with **LocalStack**. Use the same setup regardless of language/framework.

## Directory structure

```
project-root/
  docker-compose.yml                 ← defines the local infrastructure
  localstack/
    init-<service>.sh                ← LocalStack init scripts (creating buckets/queues/secrets, etc.)
  .env.development                   ← env vars for local dev (running the app directly on the host)
  .env.docker                        ← env vars used when running the app as a container (optional)
```

## docker-compose.yml

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
    image: localstack/localstack
    ports: ['4566:4566']
    environment:
      SERVICES: s3,sqs,secretsmanager   # list only the services you actually use
      DEFAULT_REGION: us-east-1
    volumes: ['./localstack:/etc/localstack/init/ready.d']
    healthcheck:
      test: ['CMD-SHELL', 'awslocal s3 ls']
      interval: 5s
      timeout: 3s
      retries: 5

  app:
    build: .
    ports: ['3000:3000']
    env_file: ['.env.docker']
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']

volumes:
  db-data:
```

> Add any other infrastructure your project needs, like `redis`, the same way (an image + a healthcheck).

## Service layout

| Service | Purpose | Port |
|---|---|---|
| `database` | An RDBMS (Postgres/MySQL, etc.) | 5432 / 3306 |
| `localstack` | Replaces cloud services (S3, SQS, Secrets Manager, SES, etc.) | 4566 |
| `app` | The application itself (optional) | project-specific |

## Health checks — required for every infrastructure service

Have the `app` service use `condition: service_healthy` in `depends_on`, so it only starts once the infrastructure is ready. A container process like `localstack` can be up while its internal services haven't finished initializing yet, so write the healthcheck to actually call the real service API (`pg_isready`, `awslocal s3 ls`, etc.).

## profiles — running the app service optionally

Set `profiles: [app]` on the `app` service so that **the default run only starts the infrastructure**, and the app itself is run directly on the host (`npm run start:dev`, `go run`, `./gradlew bootRun`, `uvicorn`, etc.). To also run the app as a container, use `--profile app`.

```bash
# start only the infrastructure (default — during development)
docker compose up -d

# start infrastructure + the app together
docker compose --profile app up -d
```

## LocalStack init scripts

```bash
#!/bin/sh
# localstack/init-aws.sh
set -e
awslocal s3 mb s3://app-files
awslocal sqs create-queue --queue-name app-events
```

- Put scripts that create whatever resources you need (an S3 bucket, an SQS queue, a Secrets Manager secret, SES sender verification, etc.) under `localstack/`.
- Mounting them to `/etc/localstack/init/ready.d/` runs them automatically once LocalStack finishes starting.
- They need execute permission: `chmod +x localstack/init-*.sh`.
- If you use SES, **sender-email verification (`awslocal ses verify-email-identity`) must be done here** — LocalStack's SES emulator, like real SES, rejects sends from an unverified sender.

## .env.development — running the app directly on the host

```env
# Database
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_USER=dev
DATABASE_PASSWORD=dev
DATABASE_NAME=app

# AWS (LocalStack)
AWS_ENDPOINT_URL=http://localhost:4566
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# App
PORT=3000
NODE_ENV=development
```

## .env.docker — when the app runs as a container

When the app runs inside the Docker Compose network, it needs to connect using the **service name** instead of `localhost`. Inside the Docker Compose network, a service name resolves as a hostname.

```env
DATABASE_HOST=database
AWS_ENDPOINT_URL=http://localstack:4566
```

## Integrating with LocalStack from the AWS SDK

If the `AWS_ENDPOINT_URL` environment variable is set, connect to that endpoint; if not, use the real cloud endpoint/the default credential chain. In local/test, always set explicit fixed credentials (`test`/`test`) too, to avoid the delay caused by the SDK's default credential discovery (IMDS, etc.).

```typescript
// creating an AWS client for SES/S3/Secrets Manager, etc. — conceptual
const client = createAwsClient({
  region: process.env.AWS_REGION ?? 'us-east-1',
  endpoint: process.env.AWS_ENDPOINT_URL,           // LocalStack if set, otherwise the default
  credentials: {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test',
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test'
  }
})
```

## How to run it

```bash
# 1. Start the infrastructure
docker compose up -d

# 2. Run the app (locally)
<the project's own run command>

# --- or ---

# start every container (including the app)
docker compose --profile app up -d

# check the logs
docker compose logs -f app

# shut everything down
docker compose --profile app down

# shut everything down + delete the data
docker compose --profile app down -v
```

---

## Principles

- **Never connect directly to an external service during local development**: use Docker Compose for the DB, and LocalStack for cloud services.
- **Set a healthcheck on every infrastructure service**: so the app only starts once the infrastructure is ready.
- **Separate out the app service via profiles**: the default run is infrastructure-only; add `--profile app` to include the app.
- **Branch the endpoint via an environment variable**: LocalStack if `AWS_ENDPOINT_URL` is set, the real cloud otherwise.
- **Keep the init scripts in the project**: commit `localstack/init-*.sh` so every developer can reproduce the same environment.
- **Pin the LocalStack image version**: the `latest` tag can change its licensing policy or behavior without notice, so use a specific version tag.
- **docker-compose.yml is for development only**: manage production infrastructure separately (Terraform, etc.).

### Related docs

- [config.md](config.md) — env-var validation, managing sensitive values
- [secret-manager.md](secret-manager.md) — the local stand-in for Secrets Manager
- [container.md](container.md) — the app's own container image
