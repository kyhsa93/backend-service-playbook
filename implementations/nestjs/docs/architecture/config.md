# Environment Configuration Pattern

### Directory Structure — Actual Code

```
src/
  config/
    app.config.ts          # PORT, NODE_ENV
    aws.config.ts          # AWS_REGION, AWS_ENDPOINT_URL, credentials
    database.config.ts     # DATABASE_URL
    jwt.config.ts          # JWT-related config (includes the Secrets Manager branch)
    llm.config.ts          # OLLAMA_BASE_URL, REFUND_CLASSIFIER_MODEL
    fraud-risk.config.ts   # FRAUD_SCORER_MODE, FRAUD_SCORER_BASE_URL
    notification.config.ts # SES_SENDER_EMAIL
    throttle.config.ts     # THROTTLE_{SHORT,MEDIUM,LONG}_{TTL_MS,LIMIT} — see rate-limiting.md
    validation.config.ts   # the environment variable validation function
```

- Split the config files by concern.
- Every config file lives in the `src/config/` directory and ends in `*.config.ts` (the harness's `config.file-naming` rule).

### Registering ConfigModule in the Root Module

```typescript
// app-module.ts — actual code (excerpt)
import { Module } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'

import { validateConfig } from '@/config/validation.config'
import { jwtConfig } from '@/config/jwt.config'

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig], validate: validateConfig }),
    OutboxModule,
    AuthModule,
    AccountModule,
  ]
})
export class AppModule {}
```

- `isGlobal: true` — lets every module inject `ConfigService` without a separate import.
- `load` — registers only the JWT config under `ConfigModule`'s namespace. Everything else, like DATABASE_URL, is read directly via the pure functions (`getDatabaseUrl()`, `getAwsRegion()`, etc.) that `src/config/*.config.ts` exports, bypassing `ConfigService` — because a value like the TypeORM `DataSource` (`data-source.ts`), which is created before the NestJS DI container exists, can't have `ConfigService` injected into it.
- `validate` — validates environment variables at app startup. Halts startup on a validation failure.

### Config Factory/Helper Functions — Actual Code

```typescript
// config/database.config.ts
export function getDatabaseUrl(): string {
  return process.env.DATABASE_URL ?? ''
}
```

```typescript
// config/jwt.config.ts (summary) — includes the Secrets Manager branch; see secret-manager.md for detail
export const jwtConfig = async () => {
  // calls Secrets Manager only in production — every other environment, including the
  // NODE_ENV=test that jest sets automatically, uses only the environment variable with no network call.
  if (process.env.NODE_ENV !== 'production') {
    return { jwt: { secret: process.env.JWT_SECRET ?? 'dev-secret', expiresIn: process.env.JWT_EXPIRES_IN ?? '1h' } }
  }
  // ... looked up from Secrets Manager (app/jwt)
}
```

```typescript
// config/aws.config.ts
export function getAwsRegion(): string { return process.env.AWS_REGION ?? 'us-east-1' }
export function getAwsEndpoint(): string | undefined { return process.env.AWS_ENDPOINT_URL }
export function getAwsCredentials() {
  if (process.env.NODE_ENV === 'production') return undefined // the SDK's default credential chain (IAM role)
  return { accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test', secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test' }
}
```

- `database.config.ts`/`aws.config.ts`/`app.config.ts`/`notification.config.ts`/`throttle.config.ts` are **pure functions** that bypass `ConfigModule` — called directly instead of being accessed via dot notation on `ConfigService`. Only `jwt.config.ts` is registered in `ConfigModule.forRoot({ load: [jwtConfig] })` and accessed through `ConfigService` (see below).

### Environment Variable Validation — class-validator

If a required environment variable is missing or has an invalid value at app startup, **terminate the process immediately**. Failing fast at the startup stage is safer than letting a bad configuration cause a runtime outage later.

```typescript
// config/validation.config.ts — actual code
import { Logger } from '@nestjs/common'
import { plainToInstance } from 'class-transformer'
import { IsNotEmpty, IsString, validateSync } from 'class-validator'

class EnvironmentVariables {
  @IsString()
  @IsNotEmpty()
  DATABASE_URL: string
}

export function validateConfig(config: Record<string, unknown>): EnvironmentVariables {
  const validated = plainToInstance(EnvironmentVariables, config, {
    enableImplicitConversion: true,
  })

  const errors = validateSync(validated, { skipMissingProperties: false })

  if (errors.length > 0) {
    Logger.error('Environment validation failed:', undefined, 'ConfigValidation')
    Logger.error(errors.map((e) => Object.values(e.constraints ?? {}).join(', ')).join('\n'), undefined, 'ConfigValidation')
    process.exit(1)
  }

  return validated
}
```

Only `DATABASE_URL` is validated here — `JWT_SECRET` is replaced by Secrets Manager in production (see [secret-manager.md](secret-manager.md)), so it isn't a target of this fail-fast validation.

- `plainToInstance`'s `enableImplicitConversion: true` — automatically converts an environment variable that comes in as a string into the type the decorator expects.
- `validateSync` — synchronous validation. NestJS's `ConfigModule`'s `validate` option expects a synchronous function.
- `process.exit(1)` on a validation failure — prevents the app from starting up in a broken configuration state. Uses `Logger.error` rather than calling `console.error` directly with no client log (consistent with observability.md's structured-logging principle).

### Using ConfigService — Limited to the JWT Configuration

```typescript
// src/auth/auth-service.ts — actual code (excerpt)
import { Injectable } from '@nestjs/common'
import { ConfigService } from '@nestjs/config'

@Injectable()
export class AuthService {
  constructor(private readonly configService: ConfigService) {}

  sign(userId: string): string {
    return jwt.sign({ userId }, this.configService.get<string>('jwt.secret')!, {
      expiresIn: this.configService.get<string>('jwt.expiresIn'),
    })
  }
}
```

- Since `ConfigService` is registered with `isGlobal: true`, it can be injected without a separate module import.
- Access nested config values via dot notation (`'jwt.secret'`).
- In production, sensitive values like the JWT secret use AWS Secrets Manager instead of an environment variable. See [secret-manager.md](secret-manager.md) for the detailed pattern.
