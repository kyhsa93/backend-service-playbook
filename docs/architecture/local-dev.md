# 로컬 개발 환경

로컬 개발 시 외부 인프라(DB, 캐시, AWS 서비스 등)를 **Docker Compose**로 실행하고, 클라우드 서비스(S3, SQS, Secrets Manager, SES 등)는 **LocalStack**으로 대체한다. 언어/프레임워크에 관계없이 동일한 구성을 사용한다.

## 디렉토리 구조

```
project-root/
  docker-compose.yml                 ← 로컬 인프라 정의
  localstack/
    init-<service>.sh                ← LocalStack 초기화 스크립트 (버킷/큐/시크릿 생성 등)
  .env.development                   ← 로컬 개발용 환경 변수 (호스트에서 앱을 직접 실행할 때)
  .env.docker                        ← 앱을 컨테이너로 실행할 때 사용하는 환경 변수 (선택)
```

## docker-compose.yml

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
    image: localstack/localstack
    ports: ['4566:4566']
    environment:
      SERVICES: s3,sqs,secretsmanager   # 실제 사용하는 서비스만 나열
      DEFAULT_REGION: us-east-1
    volumes: ['./localstack:/etc/localstack/init/ready.d']
    healthcheck:
      test: ['CMD-SHELL', 'awslocal s3 ls']
      interval: 5s
      timeout: 3s
      retries: 5

  app:
    build: .
    ports: ['3000:3000']
    env_file: ['.env.docker']
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']

volumes:
  db-data:
```

> `redis` 등 프로젝트에 필요한 추가 인프라도 같은 방식(이미지 + healthcheck)으로 추가한다.

## 서비스 구성

| 서비스 | 용도 | 포트 |
|---|---|---|
| `database` | RDBMS (Postgres/MySQL 등) | 5432 / 3306 |
| `localstack` | 클라우드 서비스 대체 (S3, SQS, Secrets Manager, SES 등) | 4566 |
| `app` | 애플리케이션 자체 (선택적) | 프로젝트별 |

## Health Check — 모든 인프라 서비스에 필수

`app` 서비스는 `depends_on`에서 `condition: service_healthy`를 사용해, 인프라가 준비된 뒤에만 기동되도록 한다. `localstack`처럼 컨테이너 프로세스는 떠 있어도 내부 서비스 초기화가 끝나지 않은 경우가 있으므로, healthcheck는 실제 서비스 API를 호출하는 형태로 작성한다 (`pg_isready`, `awslocal s3 ls` 등).

## profiles — 앱 서비스 선택적 실행

`app` 서비스에 `profiles: [app]`을 설정하여 **기본 실행 시 인프라만 기동**하고, 앱은 로컬에서 직접 실행(`npm run start:dev`, `go run`, `./gradlew bootRun`, `uvicorn` 등)한다. 앱도 컨테이너로 실행하려면 `--profile app`을 사용한다.

```bash
# 인프라만 기동 (기본 — 개발 시)
docker compose up -d

# 인프라 + 앱 함께 기동
docker compose --profile app up -d
```

## LocalStack 초기화 스크립트

```bash
#!/bin/sh
# localstack/init-aws.sh
set -e
awslocal s3 mb s3://app-files
awslocal sqs create-queue --queue-name app-events
```

- 필요한 리소스(S3 버킷, SQS 큐, Secrets Manager 시크릿, SES 발신자 검증 등)를 생성하는 스크립트를 `localstack/` 아래에 둔다.
- `/etc/localstack/init/ready.d/`에 마운트하면 LocalStack 기동 완료 시 자동 실행된다.
- 실행 권한 필요: `chmod +x localstack/init-*.sh`.
- SES를 사용하는 경우 **발신자 이메일 검증(`awslocal ses verify-email-identity`)을 반드시 여기서 해야 한다** — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자의 발송을 거부한다.

## .env.development — 호스트에서 앱을 직접 실행할 때

```env
# Database
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_USER=dev
DATABASE_PASSWORD=dev
DATABASE_NAME=app

# AWS (LocalStack)
AWS_ENDPOINT_URL=http://localhost:4566
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# App
PORT=3000
NODE_ENV=development
```

## .env.docker — 앱이 컨테이너로 실행될 때

앱이 Docker Compose 네트워크 안에서 실행되면 `localhost` 대신 **서비스명**으로 연결해야 한다. Docker Compose 네트워크 내에서는 서비스명이 호스트명으로 해석된다.

```env
DATABASE_HOST=database
AWS_ENDPOINT_URL=http://localstack:4566
```

## AWS SDK에서 LocalStack 연동

`AWS_ENDPOINT_URL` 환경 변수가 설정되면 해당 엔드포인트로 연결하고, 설정되지 않으면 실제 클라우드 엔드포인트/기본 자격증명 체인을 사용한다. 로컬/테스트에서는 자격증명도 항상 명시적으로 고정값(`test`/`test`)을 넣어, SDK의 기본 자격증명 탐색(IMDS 등)으로 인한 지연을 피한다.

```typescript
// SES/S3/Secrets Manager 등 AWS 클라이언트 생성 — 개념
const client = createAwsClient({
  region: process.env.AWS_REGION ?? 'us-east-1',
  endpoint: process.env.AWS_ENDPOINT_URL,           // 있으면 LocalStack, 없으면 기본
  credentials: {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test',
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test'
  }
})
```

## 실행 방법

```bash
# 1. 인프라 기동
docker compose up -d

# 2. 앱 실행 (로컬)
<프로젝트별 실행 명령>

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

---

## 원칙

- **로컬 개발 시 외부 서비스에 직접 연결하지 않는다**: DB는 Docker Compose, 클라우드 서비스는 LocalStack을 사용한다.
- **모든 인프라 서비스에 healthcheck를 설정한다**: 인프라가 준비된 후에 앱이 기동되도록 한다.
- **profiles로 앱 서비스를 분리한다**: 기본 실행은 인프라만, `--profile app`으로 앱 포함.
- **환경 변수로 엔드포인트를 분기한다**: `AWS_ENDPOINT_URL`이 있으면 LocalStack, 없으면 실제 클라우드.
- **초기화 스크립트는 프로젝트에 포함한다**: `localstack/init-*.sh`를 커밋해 모든 개발자가 같은 환경을 재현할 수 있도록 한다.
- **LocalStack 이미지 버전을 고정한다**: 최신 태그가 예고 없이 라이선스 정책이나 동작을 바꿀 수 있으므로 구체적인 버전 태그를 사용한다.
- **docker-compose.yml은 개발 전용이다**: 운영 인프라는 별도로 관리한다 (Terraform 등).

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, 민감값 관리
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체
- [container.md](container.md) — 앱 자체의 컨테이너 이미지
