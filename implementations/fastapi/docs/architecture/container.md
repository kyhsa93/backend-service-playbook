# 컨테이너 이미지

> 프레임워크 무관 원칙: [../../../../docs/architecture/container.md](../../../../docs/architecture/container.md)

## 현재 구현 — `implementations/fastapi/examples/Dockerfile`가 이미 존재한다

`examples/Dockerfile`, `examples/.dockerignore`가 실제로 존재하며, 아래 멀티스테이지 빌드를 그대로 구현하고 있다.

---

## 멀티스테이지 빌드

```dockerfile
# implementations/fastapi/examples/Dockerfile

# ---- Stage 1: Build ----
FROM python:3.12-slim AS build

WORKDIR /app

# 컴파일이 필요한 의존성(asyncpg 등)의 빌드 도구만 이 스테이지에 포함한다.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---- Stage 2: Production ----
FROM python:3.12-slim

WORKDIR /app

# 빌드 스테이지에서 설치된 패키지만 복사 — gcc/libpq-dev는 최종 이미지에 없다.
COPY --from=build /install /usr/local

COPY main.py ./
COPY src ./src

# 컨테이너 안에서 루트로 실행하지 않는다.
RUN useradd --create-home appuser
USER appuser

EXPOSE 8000

# exec form — uvicorn이 PID 1이 되어 SIGTERM을 즉시 수신한다.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**각 스테이지의 목적:**

| 스테이지 | 내용 | 최종 이미지 포함 |
|---------|------|---------------|
| Build | `gcc`, `libpq-dev`(asyncpg 컴파일용) + 전체 Python 패키지 | ✗ |
| Production | 설치된 패키지(`/usr/local`) + `main.py`, `src/`만 | ✓ |

`asyncpg`, `aioboto3`의 일부 하위 의존성은 C 확장을 빌드해야 하므로, `gcc`/`libpq-dev` 없이는 `pip install`이 실패한다. 이 도구들을 build 스테이지에만 두어 최종 이미지 크기와 공격 표면을 최소화한다.

---

## .dockerignore

```
# implementations/fastapi/examples/.dockerignore
__pycache__/
*.pyc
.pytest_cache/
.venv/
.git/
.env*
docker-compose.yml
localstack/
tests/
```

`tests/`, `docker-compose.yml`은 런타임에 불필요하다. `.env*`를 포함하면 로컬 개발용 민감값이 이미지에 새어 들어갈 위험이 있다.

---

## CMD — exec form으로 직접 프로세스 실행

```dockerfile
# 올바른 방식 — uvicorn을 PID 1로 직접 실행
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

# 잘못된 방식 — 쉘이 중간에 끼어 SIGTERM 전달 지연
CMD uvicorn main:app --host 0.0.0.0 --port 8000
```

exec form(`["uvicorn", ...]`)은 셸을 거치지 않고 `uvicorn` 프로세스가 PID 1이 되어 SIGTERM을 즉시 수신한다. shell form(`CMD uvicorn ...`)은 `/bin/sh -c`가 PID 1이 되어 SIGTERM이 셸에서 멈추거나 지연 전달될 수 있다.

→ `uvicorn`이 SIGTERM을 수신한 뒤 FastAPI `lifespan`의 종료 블록이 실행되는 흐름은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 환경 변수는 이미지에 포함 금지

```dockerfile
# 금지 — 이미지에 민감값 하드코딩
ENV DATABASE_URL=postgresql+asyncpg://prod-user:realpassword@prod-db/account

# 금지 — .env 파일을 이미지에 복사
COPY .env .env
```

`docker-compose.yml`이나 오케스트레이터(ECS Task Definition, Kubernetes Secret 등)를 통해 실행 시점에 주입한다. `src/config/database_config.py`의 `DatabaseConfig`(`pydantic_settings.BaseSettings`, `DATABASE_` 환경 변수 접두사)가 환경 변수에서 값을 읽는 패턴 자체는 올바르지만([config.md](config.md) 참조), 값 자체는 이미지 밖에서 와야 한다.

```bash
docker run --env-file .env.docker myapp
docker run -e DATABASE_URL=postgresql+asyncpg://... myapp
```

---

## 헬스체크 엔드포인트

오케스트레이터가 인스턴스 상태를 확인할 수 있도록 liveness/readiness 엔드포인트를 제공한다. 현재 `main.py`에는 헬스체크 라우트가 없다 — 아래 두 엔드포인트를 추가한다.

```
GET /health/live   → 200: 프로세스 생존 확인
GET /health/ready  → 200: 트래픽 수신 가능 / 503: 종료 중 또는 초기화 전
```

→ 구현 상세와 `lifespan`과의 연동은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 원칙

- **멀티스테이지 빌드 필수**: `gcc`, `libpq-dev` 등 빌드 전용 도구는 프로덕션 이미지에 포함하지 않는다.
- **non-root 사용자로 실행**: `USER appuser`.
- **.dockerignore 유지**: `tests/`, `.env*`, `.git`, `docker-compose.yml`은 반드시 제외한다.
- **CMD는 exec form**: `["uvicorn", ...]` — SIGTERM 즉시 수신.
- **환경 변수는 이미지 외부에서 주입**: 이미지 자체는 환경 무관하게 동일해야 한다.
- **헬스체크 엔드포인트 필수**: `/health/live`, `/health/ready`.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, `lifespan`에서의 헬스체크 상태 관리
- [config.md](config.md) — 환경 변수/설정 관리
- [local-dev.md](local-dev.md) — `docker-compose.yml`에 app 서비스로 통합하는 방법
