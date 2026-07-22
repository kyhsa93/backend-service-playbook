# Secret Management

Sensitive values — DB passwords, JWT secrets, external API keys — are never put directly into environment variables or code; instead, store them in a **Secrets Manager (AWS Secrets Manager, GCP Secret Manager, HashiCorp Vault, etc.)** and look them up at runtime. This is the implementation detail behind [config.md](config.md)'s "sensitive values — env vars vs. Secrets Manager" principle.

---

## Flow

```
[When the app starts]
1. SecretService: looks up the secret from Secrets Manager
2. SecretService: caches it in memory (TTL-based)
3. Any later request for the same key returns it from the cache

[When the cache expires]
4. SecretService: looks it up from Secrets Manager again → refreshes the cache
```

## SecretService — abstracted as a Technical Service

Secret lookup applies the exact same **Technical Service** pattern from [domain-service.md](domain-service.md): define an interface in the Application layer, and put the implementation in the Infrastructure layer.

```typescript
// application/service/secret-service — the interface (abstract class/interface)
abstract class SecretService {
  abstract getSecret(secretId: string): Promise<string>
}
```

```typescript
// infrastructure/secret-service-impl — the Secrets Manager implementation + TTL cache
class SecretServiceImpl implements SecretService {
  private readonly client = createSecretsManagerClient({
    endpoint: process.env.AWS_ENDPOINT_URL  // LocalStack locally; the default endpoint in prod if unset
  })
  private readonly cache = new Map<string, { value: string; expiresAt: number }>()
  private readonly ttlMs = 5 * 60 * 1000  // 5 minutes

  async getSecret(secretId: string): Promise<string> {
    const cached = this.cache.get(secretId)
    if (cached && cached.expiresAt > Date.now()) return cached.value

    const value = await this.client.getSecretValue(secretId)
    this.cache.set(secretId, { value, expiresAt: Date.now() + this.ttlMs })
    return value
  }
}
```

- **TTL cache**: requesting the same key again within the TTL returns it from the cache with no API call. Secrets Manager typically charges per call or has a rate limit, so it's never looked up on every single request with no cache.
- **Endpoint branching**: local development connects to the LocalStack endpoint, while production leaves the endpoint unset and uses the real cloud service (see [local-dev.md](local-dev.md)).

## Using a JSON-shaped secret

Store several values as JSON in one secret and access them by key. Since every secret incurs its own API call, group logically related values (like a full set of DB connection info) into a single secret.

```typescript
// Example of a value stored in Secrets Manager:
// secretId: "app/database"
// value: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

const dbSecret = JSON.parse(await secretService.getSecret('app/database'))
const host = dbSecret.host
const password = dbSecret.password
```

## Using SecretService in a config factory

Look up a secret once at app startup and inject it into the config object. Branch so local development uses environment variables while production uses Secrets Manager.

```typescript
// config/database-config — conceptual
async function loadDatabaseConfig() {
  if (process.env.NODE_ENV === 'development') {
    return {
      host: process.env.DATABASE_HOST,
      port: Number(process.env.DATABASE_PORT ?? 5432),
      username: process.env.DATABASE_USER,
      password: process.env.DATABASE_PASSWORD
    }
  }

  const secret = JSON.parse(await secretService.getSecret('app/database'))
  return {
    host: secret.host,
    port: Number(secret.port ?? 5432),
    username: secret.username,
    password: secret.password
  }
}
```

## Local development — LocalStack

Locally, don't hit the real Secrets Manager — use LocalStack instead (see [local-dev.md](local-dev.md)).

```bash
# localstack/init-aws.sh — runs automatically when LocalStack starts
awslocal secretsmanager create-secret \
  --name app/database \
  --secret-string '{"host":"localhost","port":"5432","username":"dev","password":"dev"}'

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

```yaml
# docker-compose.yml — add secretsmanager to LocalStack's SERVICES
localstack:
  image: localstack/localstack
  environment:
    SERVICES: s3,sqs,secretsmanager
```

---

## Principles

- **Never put sensitive values directly in environment variables**: look them up from Secrets Manager in production.
- **Use environment variables or LocalStack for local development**: never hit the real Secrets Manager.
- **Apply a TTL cache**: use an in-memory cache so the same secret isn't looked up repeatedly.
- **Abstract it behind a SecretService interface**: same as the Technical Service pattern — an interface in the Application layer, an implementation in the Infrastructure layer.
- **Store logically related values as JSON in a single secret**: more secrets means more API calls/cost.

### Related docs

- [config.md](config.md) — the criteria for choosing env vars vs. Secrets Manager
- [domain-service.md](domain-service.md) — the Technical Service pattern
- [local-dev.md](local-dev.md) — a LocalStack-based local dev environment
