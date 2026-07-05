# Secret 관리

DB 비밀번호, JWT secret, 외부 API 키 등 민감값은 환경 변수나 코드에 직접 넣지 않고 **Secrets Manager(AWS Secrets Manager, GCP Secret Manager, HashiCorp Vault 등)** 에 저장하여 런타임에 조회한다. [config.md](config.md)의 "민감값 — 환경 변수 vs Secrets Manager" 원칙의 구현 상세이다.

---

## 흐름

```
[앱 기동 시]
1. SecretService: Secrets Manager에서 시크릿 조회
2. SecretService: 메모리에 캐시 (TTL 기반)
3. 이후 동일 키 요청 시 캐시에서 반환

[캐시 만료 시]
4. SecretService: Secrets Manager에 다시 조회 → 캐시 갱신
```

## SecretService — Technical Service로 추상화

Secret 조회는 [domain-service.md](domain-service.md)의 **Technical Service** 패턴을 그대로 적용한다: Application 레이어에 인터페이스를 정의하고, Infrastructure 레이어에 구현체를 둔다.

```typescript
// application/service/secret-service — 인터페이스 (추상 클래스/인터페이스)
abstract class SecretService {
  abstract getSecret(secretId: string): Promise<string>
}
```

```typescript
// infrastructure/secret-service-impl — Secrets Manager 구현체 + TTL 캐시
class SecretServiceImpl implements SecretService {
  private readonly client = createSecretsManagerClient({
    endpoint: process.env.AWS_ENDPOINT_URL  // 로컬은 LocalStack, 운영은 미설정 시 기본 엔드포인트
  })
  private readonly cache = new Map<string, { value: string; expiresAt: number }>()
  private readonly ttlMs = 5 * 60 * 1000  // 5분

  async getSecret(secretId: string): Promise<string> {
    const cached = this.cache.get(secretId)
    if (cached && cached.expiresAt > Date.now()) return cached.value

    const value = await this.client.getSecretValue(secretId)
    this.cache.set(secretId, { value, expiresAt: Date.now() + this.ttlMs })
    return value
  }
}
```

- **TTL 캐시**: 동일 키를 TTL 내에 다시 요청하면 API 호출 없이 캐시에서 반환한다. Secrets Manager는 호출당 과금되거나 rate limit이 있으므로 캐시 없이 매 요청마다 조회하지 않는다.
- **엔드포인트 분기**: 로컬 개발은 LocalStack 엔드포인트로 연결하고, 운영은 엔드포인트를 지정하지 않아 실제 클라우드 서비스를 사용한다 ([local-dev.md](local-dev.md) 참고).

## JSON 형태의 시크릿 사용

여러 값을 하나의 시크릿에 JSON으로 저장하고, 키별로 접근한다. 시크릿 하나마다 API 호출이 발생하므로, 논리적으로 묶이는 값(DB 접속 정보 전체 등)은 하나의 시크릿에 모아 저장한다.

```typescript
// Secrets Manager에 저장된 값 예시:
// secretId: "app/database"
// value: {"host":"db.example.com","port":"5432","username":"admin","password":"s3cret"}

const dbSecret = JSON.parse(await secretService.getSecret('app/database'))
const host = dbSecret.host
const password = dbSecret.password
```

## 설정 팩토리에서 SecretService 사용

시크릿은 앱 기동 시 한 번 조회하여 설정 객체에 주입한다. 로컬 개발 환경에서는 환경 변수를, 운영 환경에서는 Secrets Manager를 사용하도록 분기한다.

```typescript
// config/database-config — 개념
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

## 로컬 개발 — LocalStack

로컬에서는 실제 Secrets Manager에 접근하지 않고 LocalStack으로 대체한다 ([local-dev.md](local-dev.md) 참고).

```bash
# localstack/init-aws.sh — LocalStack 기동 시 자동 실행
awslocal secretsmanager create-secret \
  --name app/database \
  --secret-string '{"host":"localhost","port":"5432","username":"dev","password":"dev"}'

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

```yaml
# docker-compose.yml — LocalStack SERVICES에 secretsmanager 추가
localstack:
  image: localstack/localstack
  environment:
    SERVICES: s3,sqs,secretsmanager
```

---

## 원칙

- **민감한 값은 환경 변수에 직접 넣지 않는다**: 운영 환경에서는 Secrets Manager에서 조회한다.
- **로컬 개발 시에는 환경 변수 또는 LocalStack을 사용한다**: 실제 Secrets Manager에 접근하지 않는다.
- **TTL 캐시를 적용한다**: 동일 시크릿을 반복 조회하지 않도록 메모리 캐시를 사용한다.
- **SecretService 인터페이스로 추상화한다**: Technical Service 패턴과 동일하게 Application 레이어에 인터페이스, Infrastructure 레이어에 구현체를 둔다.
- **논리적으로 묶이는 값은 하나의 시크릿에 JSON으로 저장한다**: 시크릿 단위가 늘어나면 API 호출/비용도 늘어난다.

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준
- [domain-service.md](domain-service.md) — Technical Service 패턴
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
