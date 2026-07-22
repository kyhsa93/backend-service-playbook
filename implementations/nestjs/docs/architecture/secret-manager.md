# Secret Management

Sensitive values like DB passwords, JWT secrets, and API keys aren't put directly into environment variables or code — they're stored in **AWS Secrets Manager** and looked up at runtime.

### Flow

```
[At app startup]
1. SecretService: looks up the secret from Secrets Manager
2. SecretService: caches it in memory (TTL-based)
3. From then on, the same key is returned from the cache

[On cache expiry]
4. SecretService: looks it up again from Secrets Manager → refreshes the cache
```

### The SecretService Interface (common/application/service/) — Actual Code

```typescript
// common/application/service/secret-service.ts
export abstract class SecretService {
  abstract getSecret(secretId: string): Promise<string>
}
```

### The SecretService Implementation (common/infrastructure/) — Actual Code

Looks up the value from Secrets Manager and applies a TTL-based in-memory cache.

```typescript
// common/infrastructure/secret-service-impl.ts
import { Injectable } from '@nestjs/common'
import { GetSecretValueCommand, SecretsManagerClient } from '@aws-sdk/client-secrets-manager'

import { SecretService } from '@/common/application/service/secret-service'
import { getAwsEndpoint } from '@/config/aws.config'

@Injectable()
export class SecretServiceImpl extends SecretService {
  private readonly client = new SecretsManagerClient({
    ...(getAwsEndpoint() ? { endpoint: getAwsEndpoint() } : {})
  })
  private readonly cache = new Map<string, { value: string; expiresAt: number }>()
  private readonly ttl = 5 * 60 * 1000  // 5 minutes

  public async getSecret(secretId: string): Promise<string> {
    const cached = this.cache.get(secretId)
    if (cached && cached.expiresAt > Date.now()) return cached.value

    const result = await this.client.send(
      new GetSecretValueCommand({ SecretId: secretId })
    )
    const value = result.SecretString ?? ''
    this.cache.set(secretId, { value, expiresAt: Date.now() + this.ttl })
    return value
  }
}
```

- **TTL cache**: requesting the same key again within 5 minutes returns it from the cache with no API call.
- **`AWS_ENDPOINT_URL` branch**: when using LocalStack, it automatically connects to the local endpoint (via `config/aws.config.ts`'s `getAwsEndpoint()` — see [config.md](config.md)).

### Using a Secret Stored as JSON

Store multiple values in a single secret as JSON, and access them per key.

```typescript
// example value stored in Secrets Manager:
// SecretId: "app/database"
// SecretString: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

// when using it
const dbSecret = JSON.parse(await this.secretService.getSecret('app/database'))
const host = dbSecret.host
const password = dbSecret.password
```

### Using SecretService in a Config Factory — Actual Code (`config/jwt.config.ts`)

A pattern where a secret is looked up once at app startup and injected into `ConfigModule`. This repo applies this pattern not to `database.config.ts` but to `jwt.config.ts` — the DB connection info is managed with a single environment variable (`DATABASE_URL`), and only the sensitive JWT secret is looked up from Secrets Manager.

```typescript
// config/jwt.config.ts
export const jwtConfig = async () => {
  // calls Secrets Manager only in production. Everywhere else (development/test, etc.),
  // it uses only the environment variable, with no network call. Since jest automatically
  // sets NODE_ENV to 'test', if only 'development' were excluded, running tests would also
  // try to reach out to real AWS and break the bootstrap — production must be the only
  // explicitly-selected branch.
  if (process.env.NODE_ENV !== 'production') {
    return {
      jwt: {
        secret: process.env.JWT_SECRET ?? 'dev-secret',
        expiresIn: process.env.JWT_EXPIRES_IN ?? '1h'
      }
    }
  }

  const { SecretsManagerClient, GetSecretValueCommand } = await import('@aws-sdk/client-secrets-manager')
  const client = new SecretsManagerClient({
    ...(getAwsEndpoint() ? { endpoint: getAwsEndpoint() } : {})
  })
  const result = await client.send(new GetSecretValueCommand({ SecretId: 'app/jwt' }))
  const secret = JSON.parse(result.SecretString ?? '{}')

  return {
    jwt: {
      secret: secret.secret,
      expiresIn: process.env.JWT_EXPIRES_IN ?? '1h'
    }
  }
}
```

If you want to bundle multiple values into a single JSON secret the way DB connection info is bundled (e.g. `app/database`), you can reuse this same pattern and add the same branch to `database.config.ts` too — this repo hasn't applied it yet (moving the DB password into Secrets Manager as well is a future extension point).

**A cross-language difference — the gating variable name and polarity differ**: this repo gates on `NODE_ENV !== 'production'` (the variable `NODE_ENV`, "local if not production"). Go gates on `APP_ENV != "production"` (a different variable name, but the same polarity), fastapi gates on `APP_ENV == "production"` (the same variable name as Go, but **the opposite polarity** — "cloud if production"), and kotlin/java-springboot gate not on an environment variable but on a Spring **profile** (`Profiles.of("prod")`). When consulting another language's docs, don't assume the name and polarity map over directly.

### Module Registration

```typescript
// app-module.ts
@Module({
  providers: [
    { provide: SecretService, useClass: SecretServiceImpl }
  ],
  exports: [SecretService]
})
```

Or split it into a `@Global()` module:

```typescript
// secret/secret-module.ts
@Global()
@Module({
  providers: [{ provide: SecretService, useClass: SecretServiceImpl }],
  exports: [SecretService]
})
export class SecretModule {}
```

### Creating a Secret in LocalStack — Actual Code

```bash
# localstack/init-secrets.sh
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

### Docker Compose — Adding secretsmanager to LocalStack's SERVICES

```yaml
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager    # secretsmanager added
```

### Principles

- **Never put sensitive values directly into environment variables**: look them up from Secrets Manager in production.
- **Use environment variables or LocalStack during local development**: never access the real Secrets Manager.
- **Apply a TTL cache**: use an in-memory cache to avoid repeatedly looking up the same secret.
- **Abstract it behind the SecretService interface**: the same Technical Service Service pattern — an abstract class in common/application/service/, an implementation in common/infrastructure/.
