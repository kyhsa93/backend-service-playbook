# Graceful Shutdown

> 프레임워크 무관 원칙: [../../../../docs/architecture/graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md)

## 현재 구현 — `lifespan`은 프로덕션 전용 Secrets Manager 조회만 하고, 종료 블록은 비어 있다

```python
# main.py — 실제 코드
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 프로덕션에서만 Secrets Manager를 호출한다 — 그 외(로컬/테스트 기본값)는
    # 네트워크 호출 없이 환경 변수(JWT_SECRET)만 사용한다.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    yield


# 스키마는 배포 파이프라인에서 `alembic upgrade head`로 적용한다 — 여기서
# Base.metadata.create_all을 호출하지 않는다(docs/architecture/persistence.md 참고).
app = FastAPI(title="Account Service", lifespan=lifespan)
```

`Base.metadata.create_all`을 호출하던 예전 구현은 이미 Alembic 마이그레이션으로 대체되었다(persistence.md 참조) — 더 이상 `lifespan`이 스키마를 만들지 않는다. 대신 프로덕션에서 JWT secret을 Secrets Manager에서 조회해 주입하는 로직이 추가되었다(secret-manager.md 참조).

`yield` 이후 종료 블록은 여전히 비어 있다 — SIGTERM 수신 시 DB 엔진(`engine.dispose()`)을 정리하지 않고, 헬스체크 엔드포인트(`/health/live`, `/health/ready`)도 없다. `lifespan`은 FastAPI가 기동/종료 훅을 제공하는 정확한 지점이므로, 이 문서 작성 시점 기준 이 격차는 코드에 남아 있지만 **구조 자체는 올바른 위치를 이미 쓰고 있다** — 종료 로직만 채우면 된다.

---

## 종료 블록 제안 — `validate_env()`는 이미 다른 위치에서 실행되고 있다

**`validate_env()`를 `lifespan`의 기동 블록 안에 두는 것은 실제 배치와 다르다.** 실제로는 `main.py` 최상단, 모듈 임포트 시점(즉 `FastAPI(...)` 인스턴스가 만들어지기도 전)에 이미 호출된다 — [config.md](config.md) 참조.

```python
# main.py — 실제 코드. lifespan보다 훨씬 앞선 모듈 임포트 시점에 호출된다
from src.config.validator import validate_env

validate_env()  # 실패 시 여기서 프로세스가 종료된다 — 이후 코드는 실행되지 않음

from fastapi import FastAPI, Request  # noqa: E402
...
```

`lifespan`은 그 뒤에 정의되고, Secrets Manager 조회(위 "현재 구현" 절)만 담당한다. 아래는 종료 블록(`engine.dispose()`, readiness 플래그, 헬스체크 엔드포인트)을 채우는 확장 제안이다 — **이 부분은 아직 구현되지 않았다.**

```python
# main.py — 종료 블록 확장 제안 (아직 구현되지 않음)
import logging
from collections.abc import AsyncGenerator

from src.database import engine

logger = logging.getLogger(__name__)

app_state = {"is_shutting_down": False}


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    # --- 기동 (validate_env()는 이미 위에서 모듈 임포트 시점에 실행되었다) ---
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    logger.info("app_started")

    yield   # --- 이 사이에 요청을 처리한다 ---

    # --- 종료 (SIGTERM 수신 → uvicorn이 lifespan의 종료 블록을 실행) ---
    app_state["is_shutting_down"] = True   # readiness를 즉시 503으로 전환
    logger.info("shutdown_initiated")

    try:
        await engine.dispose()   # DB 커넥션 풀 정리
    except Exception:
        logger.exception("shutdown_cleanup_failed")   # 정리 실패해도 예외를 다시 던지지 않는다

    logger.info("app_stopped")
```

**순서가 중요한 이유:** `is_shutting_down = True`를 리소스 정리보다 먼저 설정해야, 그 사이에 들어오는 새 요청을 readiness 프로브가 차단할 수 있다. 리소스 정리(`engine.dispose()`)는 진행 중인 요청이 끝난 뒤 안전하게 실행되도록 uvicorn이 마지막에 호출한다.

---

## Liveness / Readiness 엔드포인트

```python
# main.py — 헬스체크 라우트 추가
@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}   # 항상 200 — 종료 중에도 프로세스는 살아있다


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    if app_state["is_shutting_down"]:
        return JSONResponse(status_code=503, content={"status": "shutting_down"})
    return JSONResponse(status_code=200, content={"status": "ready"})
```

| 프로브 | 목적 | 종료 중 응답 |
|--------|------|------------|
| `GET /health/live` | 프로세스 생존 확인 | **200** (항상) |
| `GET /health/ready` | 트래픽 수신 가능 여부 | **503** (종료 중) |

**흔한 실수**: liveness가 종료 중에 503을 반환하면 오케스트레이터가 종료 진행 중인 컨테이너를 강제 재시작한다. liveness는 항상 200을 반환해야 한다.

---

## uvicorn과 SIGTERM

`uvicorn`은 SIGTERM을 수신하면 기본적으로 (1) 새 연결 수락 중단 (2) 진행 중인 요청 완료 대기 (3) `lifespan`의 종료 블록 실행 순서로 graceful shutdown을 수행한다. 이 흐름이 실제로 동작하려면 컨테이너의 `CMD`가 `uvicorn`을 PID 1로 직접 실행해야 한다.

```dockerfile
# 올바른 방식 — uvicorn이 PID 1이 되어 SIGTERM을 즉시 수신
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

→ Dockerfile 전체 구성은 [container.md](container.md) 참조.

---

## terminationGracePeriodSeconds

오케스트레이터가 SIGTERM 이후 SIGKILL을 보내기까지 대기하는 시간을 서비스의 p99 요청 처리 시간보다 여유 있게 설정한다. 이 저장소의 계좌 입출금 유스케이스는 짧은 트랜잭션이므로 기본 30초로 충분하다.

```yaml
# Kubernetes 예시
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - livenessProbe:
        httpGet: { path: /health/live, port: 8000 }
    - readinessProbe:
        httpGet: { path: /health/ready, port: 8000 }
```

---

## 원칙

- **`lifespan`은 기동과 종료를 모두 담당한다**: `yield` 이전은 기동, 이후는 종료 — 현재는 종료 블록이 비어 있다.
- **readiness 전환이 리소스 정리보다 먼저**: `is_shutting_down` 플래그를 정리 코드보다 앞서 설정한다.
- **liveness는 항상 200**: 종료 중에도 프로세스가 살아있는 한 200을 반환한다.
- **정리 코드는 예외를 삼킨다**: `engine.dispose()` 실패가 다른 정리 단계를 막지 않도록 try-except로 감싼다.
- **CMD는 exec form**: uvicorn이 PID 1로 SIGTERM을 직접 수신해야 이 모든 흐름이 동작한다.

---

### 관련 문서

- [container.md](container.md) — Dockerfile CMD, 헬스체크 엔드포인트 노출
- [config.md](config.md) — `validate_env()` fail-fast와 기동 순서
- [observability.md](observability.md) — 기동/종료 로그
