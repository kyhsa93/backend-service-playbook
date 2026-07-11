# 로컬 개발 환경

로컬 개발 시 외부 인프라(DB, S3 등)를 **Docker Compose**로 실행하고, AWS 서비스는 **LocalStack**으로 대체한다.

### 디렉토리 구조 — 실제 코드

```
implementations/nestjs/examples/
  docker-compose.yml                 ← 로컬 인프라 정의
  localstack/
    init-ses.sh                      ← SES 발신자 이메일 검증
    init-secrets.sh                  ← Secrets Manager에 app/jwt 시크릿 생성
  .env.example                       ← 커밋되는 템플릿
  .env.development                   ← 로컬 개발용 환경 변수(.gitignore, .env.example을 복사해서 만듦)
```

이 저장소는 캐시/큐(redis) 없이 Postgres + LocalStack(SES, Secrets Manager)만 쓴다 — root 예시의 redis/S3/SQS는 이 도메인(Account)에 해당하지 않는다.

### docker-compose.yml — 실제 코드

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
      SERVICES: ses,secretsmanager
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
      # 컨테이너 네트워크 안에서는 localhost 대신 서비스명으로 연결한다.
      DATABASE_URL: postgres://dev:dev@database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
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

`environment:`가 `env_file:`보다 우선 적용되므로, 로컬 값은 `.env.development`(env_file)에 두고 컨테이너 네트워크에서만 달라지는 두 값(`DATABASE_URL`, `AWS_ENDPOINT_URL`)만 `environment:`로 오버라이드한다 — 별도 `.env.docker` 파일을 만들지 않는다.

### 서비스 구성

| 서비스 | 이미지 | 용도 | 포트 |
|--------|--------|------|------|
| `database` | `postgres:16-alpine` | PostgreSQL DB | 5432 |
| `localstack` | `localstack/localstack:3.0` | AWS 서비스 대체 (SES, Secrets Manager) | 4566 |
| `app` | 프로젝트 빌드 | NestJS 앱 (선택적, `profiles: [app]`) | 3000 |

### Health Check

모든 인프라 서비스에 `healthcheck`를 설정한다. `app` 서비스는 `depends_on`에서 `condition: service_healthy`를 사용하여 인프라가 준비된 후에 기동한다.

### profiles — 앱 서비스 선택적 실행

`app` 서비스에 `profiles: [app]`을 설정하여 **기본 실행 시 인프라만 기동**하고, 앱은 로컬에서 `npm run start:dev`로 실행한다. 앱도 컨테이너로 실행하려면 `--profile app`을 사용한다.

```bash
# 인프라만 기동 (기본 — 개발 시)
docker compose up -d

# 인프라 + 앱 함께 기동
docker compose --profile app up -d
```

### LocalStack 초기화 스크립트

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

- 각 AWS 서비스마다 `localstack/init-<service>.sh`로 초기화 스크립트를 분리한다.
- `init/ready.d/`에 배치하면 LocalStack 기동 시 자동 실행된다.
- 실행 권한 필요: `chmod +x localstack/init-*.sh`

### .env.example / .env.development — 실제 코드

```env
DATABASE_URL=postgres://dev:dev@localhost:5432/app

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com

JWT_SECRET=local-dev-secret
JWT_EXPIRES_IN=1h

PORT=3000
NODE_ENV=development
```

`.env.example`은 커밋하고, 이를 복사한 `.env.development`는 `.gitignore`에 포함해 커밋하지 않는다.

### 앱이 컨테이너로 실행될 때의 환경 변수

앱이 Docker Compose 내에서 실행되면 `localhost` 대신 **서비스명**으로 연결해야 한다. 별도의 `.env.docker` 파일을 두지 않고, `docker-compose.yml`의 `app` 서비스에서 `environment:`로 두 값만 오버라이드한다(`env_file:`보다 우선 적용됨) — 위 "docker-compose.yml — 실제 코드" 참고.

```yaml
environment:
  DATABASE_URL: postgres://dev:dev@database:5432/app
  AWS_ENDPOINT_URL: http://localstack:4566
```

Docker Compose 네트워크 내에서는 서비스명이 호스트명으로 해석된다 (`database` → database 컨테이너 IP).

### AWS SDK에서 LocalStack 연동

`AWS_ENDPOINT_URL` 환경 변수가 설정되면 해당 엔드포인트로 연결한다. 운영 환경에서는 이 변수를 설정하지 않으면 기본 AWS 엔드포인트를 사용한다.

```typescript
// config/aws.config.ts — 실제 코드
export function getAwsEndpoint(): string | undefined {
  return process.env.AWS_ENDPOINT_URL
}
```

```typescript
// account/infrastructure/notification/ses-client-provider.ts — 실제 코드에서 사용
new SESClient({
  region: getAwsRegion(),
  endpoint: getAwsEndpoint(),
  credentials: getAwsCredentials()
})
```

### 실행 방법

```bash
# 1. 인프라 기동
docker compose up -d

# 2. 앱 실행 (로컬)
npm run start:dev

# --- 또는 ---

# 전체 컨테이너 기동 (앱 포함)
docker compose --profile app up -d

# 로그 확인
docker compose logs -f app

# 전체 종료
docker compose --profile app down

# 전체 종료 + 데이터 삭제
docker compose --profile app down -v
```

### 원칙

- **로컬 개발 시 외부 서비스에 직접 연결하지 않는다**: DB는 Docker Compose, AWS 서비스는 LocalStack을 사용한다.
- **healthcheck를 설정한다**: 인프라 서비스가 준비된 후에 앱이 기동되도록 한다.
- **profiles로 앱 서비스를 분리한다**: 기본 실행은 인프라만, `--profile app`으로 앱 포함.
- **환경 변수로 엔드포인트를 분기한다**: `AWS_ENDPOINT_URL`이 있으면 LocalStack, 없으면 실제 AWS.
- **초기화 스크립트는 프로젝트에 포함한다**: `localstack/init-<service>.sh`를 커밋하여 모든 개발자가 같은 환경을 재현할 수 있도록 한다.
- **docker-compose.yml은 개발 전용이다**: 운영 인프라는 별도로 관리한다.
