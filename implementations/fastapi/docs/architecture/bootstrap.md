# 앱 부트스트랩

이 저장소 FastAPI 구현의 앱 부트스트랩은 NestJS의 `main.ts` + `NestFactory.create()` 같은 전용 부트스트랩 함수가 없다 — 모듈 최상위에서 `FastAPI(...)` 인스턴스를 만들고 그 아래에 라우터/예외 핸들러를 등록하는 것으로 끝난다. 실제 `examples/main.py` 전체다.

```python
# main.py — 실제 코드
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from src.account.domain.errors import AccountError, AccountNotFoundError
from src.account.infrastructure.persistence.account_repository import Base
from src.account.interface.rest.account_router import router as account_router
from src.database import engine


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


app = FastAPI(title="Account Service", lifespan=lifespan)

app.include_router(account_router)


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
```

네 줄만으로 부트스트랩이 끝난다는 점이 NestJS와의 핵심 차이다 — DI 컨테이너를 초기화하는 단계 자체가 없다(`NestFactory.create(AppModule)`에 대응하는 코드가 없다). `FastAPI()` 생성자 호출이 곧 앱 인스턴스이고, 이후 등록은 전부 그 인스턴스에 메서드/데코레이터를 호출하는 것뿐이다.

## 구성 요소별 역할

| 코드 | 역할 |
|------|------|
| `FastAPI(title=..., lifespan=lifespan)` | 앱 인스턴스 생성. `title`은 자동 생성 OpenAPI 문서의 제목이 된다 |
| `lifespan` (`@asynccontextmanager`) | 기동/종료 훅 — `yield` 이전은 기동, 이후는 종료. 현재는 기동(테이블 생성)만 채워져 있고 종료 블록이 비어 있다. 상세: [graceful-shutdown.md](graceful-shutdown.md) |
| `app.include_router(account_router)` | `APIRouter` 등록 — Bounded Context 하나당 라우터 하나. 두 번째 도메인이 생기면 같은 방식으로 `app.include_router(user_router)`를 추가한다. 상세: [module-pattern.md](module-pattern.md) |
| `@app.exception_handler(ExcType)` | 도메인 예외 → HTTP 응답 변환. `AccountNotFoundError`(구체 타입)를 `AccountError`(상위 타입)보다 먼저 등록해 404가 400보다 우선 매칭되도록 한다. 상세: [error-handling.md](error-handling.md) |

## 등록 순서가 중요한 이유

FastAPI는 예외 타입에 대해 **가장 구체적으로 일치하는 핸들러**를 먼저 찾는다(MRO 기준이지 등록 순서 기준이 아니다). 그래도 이 저장소에서는 `AccountNotFoundError`를 `AccountError` 바로 다음에 나란히 등록해 두 예외의 관계가 읽는 사람에게 명확히 드러나도록 한다 — 순서 자체가 계층 구조의 문서 역할을 한다.

## 아직 없는 것 — CORS·미들웨어

`main.py`에는 `@app.exception_handler`만 있고 `CORSMiddleware`, 커스텀 `@app.middleware("http")`는 없다. 프론트엔드가 다른 origin에서 호출해야 한다면 다음을 추가한다.

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

이 저장소는 `title="Account Service"` 외에 `version`, `description`, 보안 스키마(Bearer 인증 표시 등)를 커스터마이징하지 않는다 — 인증 자체가 아직 구현되지 않았기 때문이다([authentication.md](authentication.md) 참조). JWT 인증이 추가되면 Swagger UI에서 토큰을 입력할 수 있도록 아래와 같이 확장한다.

```python
# main.py — JWT 인증 추가 후 Swagger에 Bearer 스킴 노출 (현재는 불필요)
from fastapi.security import HTTPBearer

app = FastAPI(
    title="Account Service",
    version="1.0.0",
    lifespan=lifespan,
)
```

FastAPI는 라우트 함수의 `Depends(HTTPBearer())` 시그니처만 보고 Swagger UI에 "Authorize" 버튼을 자동으로 추가한다 — NestJS처럼 `@ApiBearerAuth('token')`을 각 컨트롤러에 붙이거나 `DocumentBuilder.addBearerAuth()`를 호출할 필요가 없다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — `lifespan`의 종료 블록, 헬스체크 엔드포인트
- [error-handling.md](error-handling.md) — `@app.exception_handler` 등록 순서와 표준 에러 응답
- [module-pattern.md](module-pattern.md) — `APIRouter` 등록, `Depends` 기반 DI
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 파이프라인
