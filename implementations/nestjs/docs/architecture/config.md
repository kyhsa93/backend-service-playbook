# 환경 설정 패턴

### 디렉토리 구조 — 실제 코드

```
src/
  config/
    app.config.ts          # PORT, NODE_ENV
    aws.config.ts          # AWS_REGION, AWS_ENDPOINT_URL, 자격증명
    database.config.ts     # DATABASE_URL
    jwt.config.ts          # JWT 관련 설정 (Secrets Manager 분기 포함)
    notification.config.ts # SES_SENDER_EMAIL
    throttle.config.ts     # THROTTLE_{SHORT,MEDIUM,LONG}_{TTL_MS,LIMIT} — rate-limiting.md 참고
    validation.config.ts   # 환경 변수 검증 함수
```

- 관심사별로 설정 파일을 분리한다.
- 모든 설정 파일은 `src/config/` 디렉토리에 위치하고 `*.config.ts`로 끝난다(harness의 `config.file-naming` 규칙).

### 루트 모듈에 ConfigModule 등록

```typescript
// app-module.ts — 실제 코드(발췌)
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

- `isGlobal: true` — 모든 모듈에서 `ConfigService`를 별도 import 없이 주입받을 수 있다.
- `load` — JWT 설정만 `ConfigModule`의 네임스페이스로 등록한다. DATABASE_URL 등 나머지는 `ConfigService`를 거치지 않고 `src/config/*.config.ts`가 내보내는 순수 함수(`getDatabaseUrl()`, `getAwsRegion()` 등)로 직접 읽는다 — TypeORM `DataSource`(`data-source.ts`)처럼 NestJS DI 컨테이너보다 먼저 생성되는 값에는 `ConfigService` 주입이 불가능하기 때문이다.
- `validate` — 앱 기동 시 환경 변수를 검증한다. 검증 실패 시 기동을 중단한다.

### 설정 팩토리/헬퍼 함수 — 실제 코드

```typescript
// config/database.config.ts
export function getDatabaseUrl(): string {
  return process.env.DATABASE_URL ?? ''
}
```

```typescript
// config/jwt.config.ts (요약) — Secrets Manager 분기 포함, 상세는 secret-manager.md
export const jwtConfig = async () => {
  // 운영(production)에서만 Secrets Manager를 호출한다 — jest가 자동 설정하는
  // NODE_ENV=test를 포함해 그 외 환경은 네트워크 호출 없이 환경 변수만 쓴다.
  if (process.env.NODE_ENV !== 'production') {
    return { jwt: { secret: process.env.JWT_SECRET ?? 'dev-secret', expiresIn: process.env.JWT_EXPIRES_IN ?? '1h' } }
  }
  // ... Secrets Manager(app/jwt)에서 조회
}
```

```typescript
// config/aws.config.ts
export function getAwsRegion(): string { return process.env.AWS_REGION ?? 'us-east-1' }
export function getAwsEndpoint(): string | undefined { return process.env.AWS_ENDPOINT_URL }
export function getAwsCredentials() {
  if (process.env.NODE_ENV === 'production') return undefined // SDK 기본 자격증명 체인(IAM 역할)
  return { accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test', secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test' }
}
```

- `database.config.ts`/`aws.config.ts`/`app.config.ts`/`notification.config.ts`/`throttle.config.ts`는 `ConfigModule`을 거치지 않는 **순수 함수**다 — `ConfigService`에서 닷 노테이션으로 접근하는 대신 직접 호출한다. `jwt.config.ts`만 `ConfigModule.forRoot({ load: [jwtConfig] })`에 등록되어 `ConfigService`로 접근한다(아래 참고).

### 환경 변수 검증 — class-validator

앱 기동 시 필수 환경 변수가 누락되거나 잘못된 값이 들어오면 **즉시 프로세스를 종료**한다. 잘못된 설정으로 런타임에 장애가 발생하는 것보다, 기동 단계에서 빠르게 실패(fail-fast)하는 것이 안전하다.

```typescript
// config/validation.config.ts — 실제 코드
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

`DATABASE_URL`만 검증 대상이다 — `JWT_SECRET`은 프로덕션에서 Secrets Manager로 대체되므로([secret-manager.md](secret-manager.md) 참고) 이 fail-fast 검증의 대상이 아니다.

- `plainToInstance`의 `enableImplicitConversion: true` — 문자열로 들어오는 환경 변수를 데코레이터 타입에 맞게 자동 변환한다.
- `validateSync` — 동기 검증. NestJS `ConfigModule`의 `validate` 옵션은 동기 함수를 기대한다.
- 검증 실패 시 `process.exit(1)` — 잘못된 설정 상태로 앱이 기동되는 것을 방지한다. 클라이언트 로그 없이 `console.error`를 직접 쓰지 않고 `Logger.error`를 쓴다(observability.md의 구조화 로깅 원칙과 일치).

### ConfigService 사용 — JWT 설정에 한정

```typescript
// src/auth/auth-service.ts — 실제 코드(발췌)
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

- `ConfigService`는 `isGlobal: true`로 등록했으므로 별도 모듈 import 없이 주입 가능하다.
- 설정 값 접근 시 닷 노테이션(`'jwt.secret'`)으로 네스팅된 값에 접근한다.
- 프로덕션 환경에서 JWT secret 등 민감한 값은 환경 변수 대신 AWS Secrets Manager를 사용한다. 상세 패턴은 [secret-manager.md](secret-manager.md) 참조.
