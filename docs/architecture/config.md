# Configuration Management

---

## Fail-Fast — Validate env vars at startup

Validate required environment variables when the app starts, and **terminate the process immediately** on failure. Failing fast at the startup stage is far safer than letting a misconfiguration cause a runtime failure later.

```typescript
// Validating env vars at startup (conceptual)
function validateEnv() {
  const required = ['DATABASE_HOST', 'DATABASE_PORT', 'JWT_SECRET']
  const missing = required.filter((key) => !process.env[key])

  if (missing.length > 0) {
    console.error(`Missing required env vars: ${missing.join(', ')}`)
    process.exit(1)  // terminate immediately
  }
}

// called from main.ts / the app entry point
validateEnv()
await startApp()
```

**Why use `process.exit(1)` on validation failure:** if the app starts up with a bad configuration, it will fail in unexpected ways at runtime while handling requests. Fail-fast gets caught immediately in the deployment pipeline, letting operators notice the problem quickly.

---

## Separate config files per concern

Don't put all config in one file. Split it up by concern so each config's purpose is clear.

```
config/
  database.config.ts    # DB connection info
  jwt.config.ts         # JWT secret, expiration time
  s3.config.ts          # File storage settings
  queue.config.ts       # Message queue settings
  config-validator.ts   # Validates all env vars together
```

```typescript
// database.config.ts
export const databaseConfig = {
  host: process.env.DATABASE_HOST ?? 'localhost',
  port: parseInt(process.env.DATABASE_PORT ?? '5432', 10),
  username: process.env.DATABASE_USER ?? 'postgres',
  password: process.env.DATABASE_PASSWORD ?? '',
  name: process.env.DATABASE_NAME ?? 'app',
}
```

**Default-value principles:**
- Defaults that work for local development are fine
- For anything that must not be empty in production (`JWT_SECRET`, `DATABASE_PASSWORD`), leave the default as `''` and block it via validation

---

## Sensitive values — env vars vs. Secrets Manager

| Item | Recommended approach |
|------|----------|
| General settings (hostname, port, timeout) | Environment variables |
| Sensitive values (DB password, API keys, JWT secret) | Secrets Manager |

Environment variables can be exposed in container logs, process listings, or an orchestrator's UI. Sensitive values — DB passwords, external API keys, JWT secrets — should be injected at app startup using something like **AWS Secrets Manager, GCP Secret Manager, or HashiCorp Vault**.

```
App starts → look up the secret from Secrets Manager → load into memory → service starts
```

Don't hardcode secrets in code or manage them via a `.env` file. A `.env` file is for local development only, and belongs in `.gitignore`.

---

## Config access pattern

Config values are accessed only in the Infrastructure layer. The Application and Domain layers never reference config values directly.

```typescript
// correct — accessing config in the Infrastructure layer
export class OrderRepositoryImpl {
  constructor(private readonly config: DatabaseConfig) {}

  connect() {
    return createConnection({ host: this.config.host, ... })
  }
}

// wrong — accessing it directly in the Application/Domain layer
export class OrderCommandService {
  connect() {
    return createConnection({ host: process.env.DATABASE_HOST })  // forbidden
  }
}
```

---

## Principles

- **Fail-fast**: validate required env vars at startup and terminate immediately on failure.
- **Separate by concern**: split config files by domain/concern.
- **Sensitive values go in Secrets Manager**: manage passwords, API keys, and tokens through Secrets Manager, not env vars.
- **Config access belongs in the Infrastructure layer**: Application/Domain never depend on config directly.
- **`.env` is local-only**: include it in `.gitignore`, never commit it.

---

### Related docs

- [container.md](container.md) — how to inject environment variables
- [graceful-shutdown.md](graceful-shutdown.md) — the startup sequence
- [secret-manager.md](secret-manager.md) — details on looking up/caching from Secrets Manager
- [local-dev.md](local-dev.md) — setting up a local dev environment
