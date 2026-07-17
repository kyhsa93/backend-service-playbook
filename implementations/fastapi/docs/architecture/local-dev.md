# 로컬 개발 환경

> 프레임워크 무관 원칙: [../../../../docs/architecture/local-dev.md](../../../../docs/architecture/local-dev.md)

이 항목은 root 문서 기준에 맞게 구현되어 있다 — `examples/docker-compose.yml`, `examples/localstack/init-ses.sh`, `examples/localstack/init-secrets.sh`가 실제 root 원칙(Docker Compose 인프라 + LocalStack + 앱 컨테이너 프로필)을 따른다.

## 현재 구성

```
implementations/fastapi/examples/
  docker-compose.yml         ← Postgres + LocalStack(SES, Secrets Manager) + app(profiles: [app])
  .env.example                ← 커밋되는 템플릿 — 이를 복사해 .env.development를 만든다
  .gitignore                  ← .env* 패턴으로 로컬 전용 값을 커밋에서 제외
  localstack/
    init-ses.sh               ← SES 발신자 이메일 검증 스크립트
    init-secrets.sh            ← Secrets Manager에 app/jwt 시크릿 생성
```

```yaml
# docker-compose.yml — 실제 코드
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
    ports: ['8000:8000']
    env_file:
      - .env.development
    environment:
      # 컨테이너 네트워크 안에서는 localhost 대신 서비스명으로 연결한다.
      DATABASE_URL: postgresql+asyncpg://dev:dev@database:5432/app
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

```bash
# localstack/init-ses.sh
#!/bin/sh
set -e

awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

```bash
# localstack/init-secrets.sh
#!/bin/sh
set -e

awslocal secretsmanager create-secret \
  --name app/jwt \
  --secret-string '{"secret":"local-dev-secret"}'
```

**이미 root 원칙을 정확히 지키고 있는 부분:**
- 모든 인프라 서비스에 `healthcheck`가 설정되어 있다 (`pg_isready`, `awslocal ses list-identities`).
- LocalStack 이미지 버전이 `3.0`으로 고정되어 있다 (`latest` 사용 안 함).
- 초기화 스크립트(`init-ses.sh`, `init-secrets.sh`)가 저장소에 커밋되어 모든 개발자가 동일한 환경을 재현할 수 있다.
- SES 발신자 이메일 검증을 초기화 스크립트에서 미리 처리한다 — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자를 거부하기 때문이다 (`test_notification_e2e.py`의 주석 참조).
- `app` 서비스가 `profiles: [app]`로 분리되어 있어, 기본 `docker compose up`은 인프라만 기동하고 앱은 호스트에서 직접 실행하는 워크플로우를 그대로 유지하면서도, 필요하면 앱까지 컨테이너로 기동할 수 있다.
- `app` 서비스는 `container.md`의 Dockerfile을 `build: .`로 참조하고, 컨테이너 네트워크 안에서는 `localhost` 대신 서비스명(`database`, `localstack`)으로 접속하도록 환경 변수를 오버라이드한다.
- `.gitignore`에 `.env.development`, `.env.docker`가 포함되어 있어 로컬 전용 값이 커밋되지 않는다.

```
# .gitignore — 실제 코드
__pycache__/
*.pyc
.pytest_cache/
.env.development
.env.docker
```

---

## `.env.example` — 커밋되는 템플릿

```env
# .env.example — 실제 코드
# 로컬에서 uvicorn main:app --reload로 앱을 직접 실행할 때 사용한다.
# 이 파일을 복사해 .env.development로 만든다 — .env.development는 커밋하지 않는다.
# uvicorn/FastAPI는 .env 파일을 자동으로 읽지 않으므로, direnv 등으로 셸에 로드하거나
# `export $(cat .env.development | xargs) && uvicorn main:app --reload`처럼 실행한다.

DATABASE_URL=postgresql+asyncpg://dev:dev@localhost:5432/app

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com

JWT_SECRET=local-dev-secret
APP_ENV=development
```

`APP_ENV=development`(또는 미설정)에서는 `main.py`의 `lifespan`이 Secrets Manager를 호출하지 않고 `JWT_SECRET` 환경 변수를 그대로 쓴다 — `APP_ENV=production`일 때만 `localstack/init-secrets.sh`가 만든 `app/jwt` 시크릿을 조회한다([secret-manager.md](secret-manager.md) 참조).

---

## 실행 방법

```bash
# 1. 인프라 기동 (database + localstack만 — app 프로필은 기본 제외)
docker compose up -d

# 2. 앱 실행 (호스트에서 직접 — 개발 중 핫 리로드)
cp .env.example .env.development   # 최초 1회
export $(cat .env.development | xargs) && uvicorn main:app --reload --port 8000

# --- 또는 ---

# 전체 컨테이너 기동 (앱 포함)
docker compose --profile app up -d

# 로그 확인
docker compose logs -f app

# 종료
docker compose --profile app down

# 종료 + 데이터 삭제
docker compose --profile app down -v
```

---

## AWS SDK — LocalStack 연동

`SesNotificationService`(`infrastructure/notification/notification_service.py`)와 `AwsSecretService`(`common/aws_secret_service.py`)는 root 원칙대로 환경 변수 기반 엔드포인트 분기를 구현하고 있다.

```python
async with self._boto_session.client(
    "ses",
    region_name=os.getenv("AWS_REGION", "us-east-1"),
    endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,   # 있으면 LocalStack, 없으면 실제 AWS
    aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
    aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
) as ses_client:
    ...
```

`test_notification_e2e.py`는 `testcontainers.localstack.LocalStackContainer`로 이 흐름 전체(계좌 생성 → SES 발송 → LocalStack 메시지 확인)를 검증한다 — [testing.md](testing.md)의 E2E 테스트 섹션 참조.

---

## 원칙

- **로컬 개발 시 외부 서비스에 직접 연결하지 않는다**: DB는 Docker Compose, AWS 서비스는 LocalStack.
- **모든 인프라 서비스에 healthcheck를 설정한다**.
- **LocalStack 이미지 버전을 고정한다**: `3.0`으로 고정되어 있다.
- **초기화 스크립트는 저장소에 커밋한다**: `localstack/init-*.sh`.
- **profiles로 앱 서비스를 분리한다** — `app` 서비스는 `profiles: [app]`로 기본 기동에서 제외된다.
- **`.env*`는 커밋하지 않는다**: `.gitignore`에 포함되어 있다.

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, `.env` 파일 관리
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체 (LocalStack)
- [container.md](container.md) — 앱 자체의 컨테이너 이미지(Dockerfile)
- [testing.md](testing.md) — testcontainers 기반 E2E 테스트
