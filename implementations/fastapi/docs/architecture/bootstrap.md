# 앱 부트스트랩

이 저장소 FastAPI 구현의 앱 부트스트랩은 NestJS의 `main.ts` + `NestFactory.create()` 같은 전용 부트스트랩 함수가 없다 — 모듈 최상위에서 `FastAPI(...)` 인스턴스를 만들고 그 아래에 라우터/예외 핸들러를 등록하는 것으로 끝난다. 실제 `examples/main.py` 전체다.

```python
# main.py — 실제 코드
import logging
import os
import time
from contextlib import asynccontextmanager

from src.config.validator import validate_env

validate_env()  # 실패 시 여기서 프로세스가 종료된다 — 이후 코드는 실행되지 않음

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402

from src.account.domain.errors import AccountError, AccountNotFoundError  # noqa: E402
from src.account.interface.rest.account_router import router as account_router  # noqa: E402
from src.auth.infrastructure.jwt_auth_service import set_jwt_secret  # noqa: E402
from src.auth.interface.rest.auth_router import router as auth_router  # noqa: E402
from src.common.aws_secret_service import AwsSecretService  # noqa: E402
from src.common.correlation import generate_correlation_id, set_correlation_id  # noqa: E402
from src.common.logging_config import configure_logging  # noqa: E402

configure_logging()
logger = logging.getLogger(__name__)


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

app.include_router(auth_router)
app.include_router(account_router)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    correlation_id = request.headers.get("x-correlation-id") or generate_correlation_id()
    set_correlation_id(correlation_id)

    start = time.monotonic()
    response = await call_next(request)
    duration_ms = (time.monotonic() - start) * 1000

    response.headers["x-correlation-id"] = correlation_id
    logger.info(
        "%s %s",
        request.method,
        request.url.path,
        extra={
            "status_code": response.status_code,
            "duration_ms": round(duration_ms, 1),
            "correlation_id": correlation_id,
        },
    )
    return response


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
```

이 코드가 짧은 전용 부트스트랩 함수 없이 모듈 최상위 코드만으로 끝난다는 점이 NestJS와의 핵심 차이다 — DI 컨테이너를 초기화하는 단계 자체가 없다(`NestFactory.create(AppModule)`에 대응하는 코드가 없다). `FastAPI()` 생성자 호출이 곧 앱 인스턴스이고, 이후 등록은 전부 그 인스턴스에 메서드/데코레이터를 호출하는 것뿐이다. `validate_env()`가 `FastAPI` import보다도 앞서 호출된다는 점도 특징이다 — fail-fast가 앱 프레임워크 자체보다 먼저 실행되어야 하기 때문이다([config.md](config.md), [graceful-shutdown.md](graceful-shutdown.md) 참조).

## 구성 요소별 역할

| 코드 | 역할 |
|------|------|
| `validate_env()` (모듈 최상단) | fail-fast: 필수 환경 변수(`DatabaseConfig`) 검증, 실패 시 `sys.exit(1)`. 상세: [config.md](config.md) |
| `configure_logging()` | 구조화 JSON 로깅 설정. 상세: [observability.md](observability.md) |
| `FastAPI(title=..., lifespan=lifespan)` | 앱 인스턴스 생성. `title`은 자동 생성 OpenAPI 문서의 제목이 된다 |
| `lifespan` (`@asynccontextmanager`) | 기동/종료 훅 — `yield` 이전은 기동, 이후는 종료. 현재는 프로덕션에서 Secrets Manager로 JWT secret을 조회하는 것만 하고, 종료 블록은 비어 있다. 상세: [graceful-shutdown.md](graceful-shutdown.md) |
| `app.include_router(auth_router)`, `app.include_router(account_router)` | `APIRouter` 등록 — Bounded Context/공유 모듈 하나당 라우터 하나. 상세: [module-pattern.md](module-pattern.md) |
| `correlation_id_middleware` (`@app.middleware("http")`) | 모든 요청에 Correlation ID 주입 + 요청 로깅. 상세: [cross-cutting-concerns.md](cross-cutting-concerns.md) |
| `@app.exception_handler(ExcType)` | 도메인 예외 → HTTP 응답 변환. `AccountNotFoundError`(구체 타입)를 `AccountError`(상위 타입)보다 먼저 등록해 404가 400보다 우선 매칭되도록 한다. 상세: [error-handling.md](error-handling.md) |

## 등록 순서가 중요한 이유

FastAPI는 예외 타입에 대해 **가장 구체적으로 일치하는 핸들러**를 먼저 찾는다(MRO 기준이지 등록 순서 기준이 아니다). 그래도 이 저장소에서는 `AccountNotFoundError`를 `AccountError` 바로 다음에 나란히 등록해 두 예외의 관계가 읽는 사람에게 명확히 드러나도록 한다 — 순서 자체가 계층 구조의 문서 역할을 한다.

## 아직 없는 것 — CORS

`main.py`는 이미 `correlation_id_middleware`(`@app.middleware("http")`)를 갖추고 있지만, `CORSMiddleware`는 아직 없다. 프론트엔드가 다른 origin에서 호출해야 한다면 다음을 추가한다.

```python
# main.py — CORS가 필요해지면 추가 (현재 examples/에는 없음)
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.getenv("CORS_ORIGIN", "*").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

Correlation ID·요청 로깅 미들웨어는 [cross-cutting-concerns.md](cross-cutting-concerns.md)에서 다룬다. Rate limiting 미들웨어는 [rate-limiting.md](rate-limiting.md) 참조.

## FastAPI의 내장 이점 — Swagger를 직접 설정할 필요가 없다

NestJS는 API 문서를 노출하려면 `main.ts`에서 `DocumentBuilder`/`SwaggerModule.setup()`을 명시적으로 조립해야 한다([nestjs bootstrap.md](../../../nestjs/docs/architecture/bootstrap.md) 참조). FastAPI는 `FastAPI(title=...)`만 생성해도 Pydantic 모델과 라우트 시그니처로부터 OpenAPI 스키마를 자동 생성하고, 별도 설정 없이 다음 두 경로가 즉시 열린다.

| 경로 | 내용 |
|------|------|
| `/docs` | Swagger UI |
| `/redoc` | ReDoc |
| `/openapi.json` | 원본 OpenAPI 스키마 |

이 저장소는 `title="Account Service"` 외에 `version`, `description`을 커스터마이징하지 않는다. JWT 인증(`src/auth/interface/rest/dependencies.py`의 `HTTPBearer` 기반 `get_current_user` — [authentication.md](authentication.md) 참조)은 이미 구현되어 있고, FastAPI가 라우트 함수의 `Depends(HTTPBearer())` 시그니처만 보고 Swagger UI에 "Authorize" 버튼을 자동으로 추가한다 — NestJS처럼 `@ApiBearerAuth('token')`을 각 컨트롤러에 붙이거나 `DocumentBuilder.addBearerAuth()`를 호출할 필요가 없다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — `lifespan`의 종료 블록, 헬스체크 엔드포인트
- [error-handling.md](error-handling.md) — `@app.exception_handler` 등록 순서와 표준 에러 응답
- [module-pattern.md](module-pattern.md) — `APIRouter` 등록, `Depends` 기반 DI
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 파이프라인
