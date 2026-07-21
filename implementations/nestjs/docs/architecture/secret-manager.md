# Secret 관리

DB 비밀번호, JWT 시크릿, API 키 등 민감한 값은 환경 변수나 코드에 직접 넣지 않고 **AWS Secrets Manager**에 저장하여 런타임에 조회한다.

### 흐름

```
[앱 기동 시]
1. SecretService: Secrets Manager에서 시크릿 조회
2. SecretService: 메모리에 캐시 (TTL 기반)
3. 이후 동일 키 요청 시 캐시에서 반환

[캐시 만료 시]
4. SecretService: Secrets Manager에 다시 조회 → 캐시 갱신
```

### SecretService 인터페이스 (common/application/service/) — 실제 코드

```typescript
// common/application/service/secret-service.ts
export abstract class SecretService {
  abstract getSecret(secretId: string): Promise<string>
}
```

### SecretService 구현체 (common/infrastructure/) — 실제 코드

Secrets Manager에서 값을 조회하고, TTL 기반 메모리 캐시를 적용한다.

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
  private readonly ttl = 5 * 60 * 1000  // 5분

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

- **TTL 캐시**: 동일 키를 5분 내에 다시 요청하면 API 호출 없이 캐시에서 반환한다.
- **`AWS_ENDPOINT_URL` 분기**: LocalStack 사용 시 자동으로 로컬 엔드포인트로 연결한다(`config/aws.config.ts`의 `getAwsEndpoint()`를 거친다 — [config.md](config.md) 참고).

### JSON 형태의 시크릿 사용

여러 값을 하나의 시크릿에 JSON으로 저장하고, 키별로 접근한다.

```typescript
// Secrets Manager에 저장된 값 예시:
// SecretId: "app/database"
// SecretString: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

// 사용 시
const dbSecret = JSON.parse(await this.secretService.getSecret('app/database'))
const host = dbSecret.host
const password = dbSecret.password
```

### 설정 팩토리에서 SecretService 사용 — 실제 코드 (`config/jwt.config.ts`)

시크릿을 앱 기동 시 한 번 조회하여 `ConfigModule`에 주입하는 패턴. 이 저장소는 `database.config.ts`가 아니라 `jwt.config.ts`에 이 패턴을 적용한다 — DB 접속 정보는 환경 변수(`DATABASE_URL`) 하나로 관리하고, 민감값인 JWT secret만 Secrets Manager에서 조회한다.

```typescript
// config/jwt.config.ts
export const jwtConfig = async () => {
  // 운영(production)에서만 Secrets Manager를 호출한다 — 그 외(development/test 등)는
  // 네트워크 호출 없이 환경 변수만 사용한다. jest는 NODE_ENV를 자동으로 'test'로
  // 설정하므로, 'development'만 예외 처리하면 테스트 실행 시에도 실제 AWS로 나가려
  // 시도해 부트스트랩이 깨진다 — 반드시 production만 명시적으로 선택해야 한다.
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

DB 접속 정보처럼 여러 값을 하나의 시크릿에 JSON으로 묶어 관리하고 싶다면(예: `app/database`), 위 패턴을 그대로 재사용해 `database.config.ts`에도 같은 분기를 추가할 수 있다 — 아직 이 저장소에는 적용하지 않았다(DB 비밀번호까지 Secrets Manager로 옮기는 것은 향후 확장 지점).

**언어 간 차이 — 게이팅 변수명·극성이 다르다**: 이 저장소는 `NODE_ENV !== 'production'`(변수 `NODE_ENV`, "production이 아니면 로컬")로 게이팅한다. go는 `APP_ENV != "production"`(변수명이 다르지만 극성은 동일), fastapi는 `APP_ENV == "production"`(변수명은 go와 같지만 **극성이 반대** — "production이면 클라우드"), kotlin/java-springboot는 환경 변수가 아니라 Spring **profile**(`Profiles.of("prod")`)로 게이팅한다. 다른 언어 문서를 참고할 때 이름과 극성이 그대로 대응된다고 가정하지 않는다.

### Module 등록

```typescript
// app-module.ts
@Module({
  providers: [
    { provide: SecretService, useClass: SecretServiceImpl }
  ],
  exports: [SecretService]
})
```

또는 `@Global()` 모듈로 분리:

```typescript
// secret/secret-module.ts
@Global()
@Module({
  providers: [{ provide: SecretService, useClass: SecretServiceImpl }],
  exports: [SecretService]
})
export class SecretModule {}
```

### LocalStack에서 시크릿 생성 — 실제 코드

```bash
# localstack/init-secrets.sh
awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

### Docker Compose — LocalStack SERVICES에 secretsmanager 추가

```yaml
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager    # secretsmanager 추가됨
```

### 원칙

- **민감한 값은 환경 변수에 직접 넣지 않는다**: 운영 환경에서는 Secrets Manager에서 조회한다.
- **로컬 개발 시에는 환경 변수 또는 LocalStack을 사용한다**: 실제 Secrets Manager에 접근하지 않는다.
- **TTL 캐시를 적용한다**: 동일 시크릿을 반복 조회하지 않도록 메모리 캐시를 사용한다.
- **SecretService 인터페이스로 추상화한다**: 기술 인프라 Service 패턴과 동일하게 common/application/service/에 abstract class, common/infrastructure/에 구현체.
