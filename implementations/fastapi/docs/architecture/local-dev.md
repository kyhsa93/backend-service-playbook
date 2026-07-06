# 로컬 개발 환경

> 프레임워크 무관 원칙: [../../../../docs/architecture/local-dev.md](../../../../docs/architecture/local-dev.md)

이 항목은 root 문서 기준으로 이미 잘 구현되어 있다 — `examples/docker-compose.yml`과 `examples/localstack/init-ses.sh`가 실제 root 원칙(Docker Compose 인프라 + LocalStack)을 따른다.

## 현재 구성

```
implementations/fastapi/examples/
  docker-compose.yml         ← Postgres + LocalStack(SES)
  localstack/
    init-ses.sh               ← SES 발신자 이메일 검증 스크립트
```

```yaml
# docker-compose.yml
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
      SERVICES: ses
      DEFAULT_REGION: us-east-1
    volumes: ['./localstack:/etc/localstack/init/ready.d']
    healthcheck:
      test: ['CMD-SHELL', 'awslocal ses list-identities']
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  db-data:
```

```bash
# localstack/init-ses.sh
#!/bin/sh
set -e

awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

**이미 root 원칙을 정확히 지키고 있는 부분:**
- 모든 인프라 서비스에 `healthcheck`가 설정되어 있다 (`pg_isready`, `awslocal ses list-identities`).
- LocalStack 이미지 버전이 `3.0`으로 고정되어 있다 (`latest` 사용 안 함).
- 초기화 스크립트(`init-ses.sh`)가 저장소에 커밋되어 모든 개발자가 동일한 환경을 재현할 수 있다.
- SES 발신자 이메일 검증을 초기화 스크립트에서 미리 처리한다 — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자를 거부하기 때문이다 (`test_notification_e2e.py`의 주석 참조).

---

## 알려진 격차 — `app` 서비스, `.env` 파일, `profiles`가 없다

현재 `docker-compose.yml`에는 `database`와 `localstack`만 있고, root가 권장하는 `app` 서비스(`profiles: [app]`)와 `.env.development`/`.env.docker` 파일이 없다. 앱은 항상 호스트에서 직접 `uvicorn main:app --reload`로 실행하는 것이 전제되어 있다 — 이는 개발 환경에서는 실용적인 선택이지만, root가 명시하는 "앱도 컨테이너로 실행할 수 있는 선택지"가 빠져 있다.

### 올바른 확장 — `app` 서비스 추가

```yaml
# docker-compose.yml — 확장 제안
services:
  database: { ... }        # 변경 없음
  localstack: { ... }      # 변경 없음

  app:
    build: .                # container.md의 Dockerfile
    ports: ['8000:8000']
    env_file: ['.env.docker']
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']       # 기본 `docker compose up`에서는 기동되지 않음

volumes:
  db-data:
```

```bash
# 인프라만 기동 (기본 — 로컬에서 uvicorn을 직접 실행할 때)
docker compose up -d

# 인프라 + 앱까지 컨테이너로 기동
docker compose --profile app up -d
```

### `.env.development` — 호스트에서 `uvicorn`을 직접 실행할 때

```env
DATABASE_URL=postgresql+asyncpg://dev:dev@localhost:5432/app

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
```

### `.env.docker` — 앱이 `docker compose --profile app` 컨테이너 안에서 실행될 때

Docker Compose 네트워크 안에서는 `localhost` 대신 서비스명을 호스트명으로 사용해야 한다.

```env
DATABASE_URL=postgresql+asyncpg://dev:dev@database:5432/app
AWS_ENDPOINT_URL=http://localstack:4566
```

`examples/.gitignore`는 이미 `.env*` 패턴 없이 `__pycache__/`, `*.pyc`, `.pytest_cache/`만 무시하고 있다 — `.env.development`/`.env.docker`를 추가하려면 `.gitignore`에도 `.env*`를 추가해야 한다([config.md](config.md) 참조).

---

## 실행 방법 (확장 이후)

```bash
# 1. 인프라 기동
docker compose up -d

# 2. 앱 실행 (호스트에서 직접 — 개발 중 핫 리로드)
uvicorn main:app --reload --port 8000

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

## AWS SDK — LocalStack 연동 (이미 올바르게 구현됨)

`SesNotificationService`(`infrastructure/notification/notification_service.py`)는 이미 root 원칙대로 환경 변수 기반 엔드포인트 분기를 구현하고 있다.

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
- **모든 인프라 서비스에 healthcheck를 설정한다**: 이미 지켜지고 있다.
- **LocalStack 이미지 버전을 고정한다**: 이미 `3.0`으로 고정되어 있다.
- **초기화 스크립트는 저장소에 커밋한다**: `localstack/init-*.sh`.
- **profiles로 앱 서비스를 분리한다**: 현재 격차 — `app` 서비스와 `.env.*` 파일을 추가해야 한다.

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, `.env` 파일 관리
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체 (LocalStack)
- [container.md](container.md) — 앱 자체의 컨테이너 이미지(Dockerfile)
- [testing.md](testing.md) — testcontainers 기반 E2E 테스트
