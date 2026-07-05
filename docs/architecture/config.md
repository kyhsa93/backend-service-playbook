# 환경 설정 관리

---

## Fail-Fast — 기동 시 환경 변수 검증

앱 기동 시 필수 환경 변수를 검증하고 실패하면 **즉시 프로세스를 종료**한다. 잘못된 설정으로 런타임에 장애가 발생하는 것보다 기동 단계에서 빠르게 실패(fail-fast)하는 것이 훨씬 안전하다.

```typescript
// 기동 시 환경 변수 검증 (개념)
function validateEnv() {
  const required = ['DATABASE_HOST', 'DATABASE_PORT', 'JWT_SECRET']
  const missing = required.filter((key) => !process.env[key])

  if (missing.length > 0) {
    console.error(`Missing required env vars: ${missing.join(', ')}`)
    process.exit(1)  // 즉시 종료
  }
}

// main.ts / app 진입점에서 호출
validateEnv()
await startApp()
```

**검증 실패 시 `process.exit(1)`을 사용하는 이유:** 잘못된 설정으로 앱이 기동되면 요청을 처리하다가 런타임에 예상치 못한 방식으로 실패한다. fail-fast는 배포 파이프라인에서 즉시 감지되고, 운영자가 문제를 빠르게 인지할 수 있게 한다.

---

## 관심사별 설정 파일 분리

모든 설정을 하나의 파일에 담지 않는다. 관심사별로 분리하여 각 설정의 목적을 명확히 한다.

```
config/
  database.config.ts    # DB 연결 정보
  jwt.config.ts         # JWT secret, 만료 시간
  s3.config.ts          # 파일 스토리지 설정
  queue.config.ts       # 메시지 큐 설정
  config-validator.ts   # 전체 환경 변수 검증
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

**기본값 설정 원칙:**
- 로컬 개발에서 동작하는 기본값은 허용
- 프로덕션에서 빈 값이면 안 되는 항목(`JWT_SECRET`, `DATABASE_PASSWORD`)은 기본값을 `''`로 두고 검증으로 차단

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 |
|------|----------|
| 일반 설정 (호스트명, 포트, 타임아웃) | 환경 변수 |
| 민감값 (DB 비밀번호, API 키, JWT secret) | Secrets Manager |

환경 변수는 컨테이너 로그, 프로세스 목록, 오케스트레이터 UI에서 노출될 수 있다. DB 비밀번호, 외부 API 키, JWT secret 등 민감값은 **AWS Secrets Manager, GCP Secret Manager, HashiCorp Vault** 등을 사용하여 앱 기동 시 주입한다.

```
앱 기동 → Secrets Manager에서 시크릿 조회 → 메모리에 로드 → 서비스 시작
```

시크릿을 코드에 하드코딩하거나 `.env` 파일로 관리하지 않는다. `.env` 파일은 로컬 개발 전용이며 `.gitignore`에 포함한다.

---

## 설정 접근 패턴

설정 값은 Infrastructure 레이어에서만 접근한다. Application, Domain 레이어는 설정 값을 직접 참조하지 않는다.

```typescript
// 올바른 방식 — Infrastructure 레이어에서 설정 접근
export class OrderRepositoryImpl {
  constructor(private readonly config: DatabaseConfig) {}

  connect() {
    return createConnection({ host: this.config.host, ... })
  }
}

// 잘못된 방식 — Application/Domain 레이어에서 직접 접근
export class OrderCommandService {
  connect() {
    return createConnection({ host: process.env.DATABASE_HOST })  // 금지
  }
}
```

---

## 원칙

- **Fail-fast**: 기동 시 필수 환경 변수를 검증하고 실패하면 즉시 종료한다.
- **관심사별 분리**: 설정 파일을 도메인/관심사별로 나눈다.
- **민감값은 Secrets Manager**: 비밀번호, API 키, 토큰은 환경 변수가 아닌 Secrets Manager로 관리한다.
- **설정 접근은 Infrastructure 레이어**: Application/Domain은 설정에 직접 의존하지 않는다.
- **`.env`는 로컬 전용**: `.gitignore`에 포함, 절대 커밋하지 않는다.

---

### 관련 문서

- [container.md](container.md) — 환경 변수 주입 방법
- [graceful-shutdown.md](graceful-shutdown.md) — 기동 순서
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
