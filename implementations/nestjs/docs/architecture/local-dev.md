# Local Development Environment

For local development, run external infrastructure (DB, S3, etc.) with **Docker Compose**, and replace AWS services with **LocalStack**.

### Directory Structure — Actual Code

```
implementations/nestjs/examples/
  docker-compose.yml                 ← the local infrastructure definition
  localstack/
    init-ses.sh                      ← verify the SES sender email
    init-secrets.sh                  ← create the app/jwt secret in Secrets Manager
    init-sqs.sh                      ← create the domain-events queue + DLQ for OutboxPoller/OutboxConsumer
  .env.example                       ← the committed template
  .env.development                   ← local development environment variables (.gitignore, made by copying .env.example)
```

This repo uses only Postgres + LocalStack (SES, Secrets Manager, SQS), with no cache (redis) or S3 — SQS is the actual queue used by `docs/architecture/domain-events.md`'s OutboxPoller/OutboxConsumer, and the redis/S3 in the root example don't apply to this domain (Account/Card/Payment).

### docker-compose.yml — Actual Code

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
    ports: ['3000:3000']
    env_file:
      - .env.development
    environment:
      # inside the container network, connect via the service name instead of localhost.
      DATABASE_URL: postgres://dev:dev@database:5432/app
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

Since `environment:` takes precedence over `env_file:`, keep local values in `.env.development` (env_file), and override via `environment:` only the two values that differ inside the container network (`DATABASE_URL`, `AWS_ENDPOINT_URL`) — no separate `.env.docker` file is created.

### Service Composition

| Service | Image | Purpose | Port |
|--------|--------|------|------|
| `database` | `postgres:16-alpine` | The PostgreSQL DB | 5432 |
| `localstack` | `localstack/localstack:3.0` | Replaces AWS services (SES, Secrets Manager, SQS) | 4566 |
| `app` | The project build | The NestJS app (optional, `profiles: [app]`) | 3000 |

### Health Check

Every infrastructure service has a `healthcheck` configured. The `app` service uses `condition: service_healthy` in `depends_on` so it starts only once the infrastructure is ready.

### profiles — Optionally Running the App Service

Setting `profiles: [app]` on the `app` service means **only the infrastructure starts by default**, and the app runs locally via `npm run start:dev`. Use `--profile app` to run the app as a container too.

```bash
# start only the infrastructure (default — for development)
docker compose up -d

# start the infrastructure + the app together
docker compose --profile app up -d
```

### LocalStack Init Scripts

```bash
#!/bin/sh
# localstack/init-ses.sh
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

```sh
#!/bin/sh
# localstack/init-secrets.sh
set -e
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

- Split the init script per AWS service as `localstack/init-<service>.sh`.
- Placing it in `init/ready.d/` makes it run automatically when LocalStack starts.
- Execute permission required: `chmod +x localstack/init-*.sh`

### .env.example / .env.development — Actual Code

```env
DATABASE_URL=postgres://dev:dev@localhost:5432/app

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

JWT_SECRET=local-dev-secret
JWT_EXPIRES_IN=1h

PORT=3000
NODE_ENV=development
```

`.env.example` is committed, and `.env.development` (a copy of it) is listed in `.gitignore` and never committed.

### Environment Variables When the App Runs as a Container

When the app runs inside Docker Compose, it must connect via the **service name** instead of `localhost`. Rather than keeping a separate `.env.docker` file, only the two values that differ are overridden via `environment:` in the `app` service of `docker-compose.yml` (which takes precedence over `env_file:`) — see "docker-compose.yml — Actual Code" above.

```yaml
environment:
  DATABASE_URL: postgres://dev:dev@database:5432/app
  AWS_ENDPOINT_URL: http://localstack:4566
```

Within the Docker Compose network, a service name resolves as a hostname (`database` → the database container's IP).

### LocalStack Integration in the AWS SDK

If the `AWS_ENDPOINT_URL` environment variable is set, it connects to that endpoint. In production, leaving this variable unset falls back to the default AWS endpoint.

```typescript
// config/aws.config.ts — actual code
export function getAwsEndpoint(): string | undefined {
  return process.env.AWS_ENDPOINT_URL
}
```

```typescript
// account/infrastructure/notification/ses-client-provider.ts — used in the actual code
new SESClient({
  region: getAwsRegion(),
  endpoint: getAwsEndpoint(),
  credentials: getAwsCredentials()
})
```

### How to Run

```bash
# 1. start the infrastructure
docker compose up -d

# 2. run the app (locally)
npm run start:dev

# --- or ---

# start every container (including the app)
docker compose --profile app up -d

# check the logs
docker compose logs -f app

# stop everything
docker compose --profile app down

# stop everything + delete data
docker compose --profile app down -v
```

### Principles

- **Never connect directly to an external service during local development**: use Docker Compose for the DB and LocalStack for AWS services.
- **Configure a healthcheck**: make sure the app starts only after the infrastructure services are ready.
- **Separate the app service via profiles**: the default run starts only the infrastructure; include the app with `--profile app`.
- **Branch the endpoint via an environment variable**: use LocalStack if `AWS_ENDPOINT_URL` is set, otherwise real AWS.
- **Include the init scripts in the project**: commit `localstack/init-<service>.sh` so every developer can reproduce the same environment.
- **docker-compose.yml is development-only**: production infrastructure is managed separately.
