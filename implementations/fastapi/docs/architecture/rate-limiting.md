# Rate Limiting

> Rate Limiting의 언어 무관 원칙(전역 기본값, 쓰기/읽기 차등, 내부 엔드포인트 제외, 응답 헤더)은 root [conventions.md](../../../../docs/conventions.md) "4. Rate Limiting 원칙" 절을 참조한다. 이 문서는 그 원칙을 FastAPI로 구현하는 방법을 다룬다.

## 알려진 격차 — 이 저장소에 Rate Limiting이 구현되어 있지 않다

`examples/requirements.txt`에는 `fastapi`, `uvicorn`, `sqlalchemy`, `asyncpg`, `pydantic`, `aioboto3`만 있고 rate limiting 라이브러리가 없다.

```
# examples/requirements.txt — 실제 내용, rate limiting 관련 패키지 없음
fastapi
uvicorn[standard]
sqlalchemy
asyncpg
pydantic[email]
aioboto3

pytest
pytest-asyncio
httpx
testcontainers[localstack]
```

아래는 이 저장소에 아직 반영되지 않은 **forward-looking 가이드**다 — 실제로 도입하려면 `requirements.txt`에 패키지를 추가하는 코드 변경이 필요하다.

---

## `slowapi` — Starlette 기반, FastAPI에 가장 관용적인 선택

FastAPI는 Starlette 위에서 동작하므로, NestJS의 `@nestjs/throttler`처럼 프레임워크가 미들웨어 등록 지점(`app.add_middleware`)과 예외 처리 지점(`@app.exception_handler`)을 이미 제공한다. `slowapi`는 이 두 지점에 얹는 얇은 래퍼로, Redis 없이 in-memory로도 동작해 로컬 개발과 잘 맞는다.

```bash
pip install slowapi
```

### 전역 등록 — `main.py`

```python
# main.py — 전역 기본 제한 (도입 시 추가)
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

limiter = Limiter(key_func=get_remote_address, default_limits=["100/minute"])

app = FastAPI(title="Account Service", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

app.include_router(account_router)
```

`key_func=get_remote_address`는 클라이언트 IP 기준으로 제한한다. 인증이 도입되면([authentication.md](authentication.md)) `Depends(get_current_user)`로 얻은 사용자 ID 기준으로 바꾸는 것이 더 정확하다 — IP는 NAT/프록시 뒤에서 여러 사용자가 공유할 수 있다.

```python
# 인증 도입 이후 — 사용자 ID 기준 제한으로 교체
def _rate_limit_key(request: Request) -> str:
    return request.headers.get("x-user-id") or get_remote_address(request)

limiter = Limiter(key_func=_rate_limit_key, default_limits=["100/minute"])
```

### 엔드포인트별 제한 — 데코레이터

`@router.post`보다 `@limiter.limit(...)`를 **먼저**(데코레이터는 아래에서 위로 적용되므로 실질적으로는 바깥쪽에) 선언해야 한다. `slowapi`는 라우트 함수 시그니처에 `request: Request` 파라미터가 있어야 동작한다.

```python
# src/account/interface/rest/account_router.py — 도입 시
from fastapi import Request

@router.post("/{account_id}/withdraw", status_code=201, response_model=TransactionResponse)
@limiter.limit("5/minute")   # 쓰기 API는 읽기보다 엄격하게 (conventions.md 원칙)
async def withdraw(
    request: Request,   # slowapi가 이 파라미터로 클라이언트를 식별
    account_id: str,
    body: WithdrawRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> TransactionResponse:
    ...


@router.get("/{account_id}")
@limiter.limit("60/minute")   # 조회는 더 관대하게
async def get_account(request: Request, account_id: str, ...) -> GetAccountResponse:
    ...
```

### 내부/헬스체크 엔드포인트 제외

`slowapi`에는 NestJS의 `@SkipThrottle()` 같은 전용 데코레이터가 없다 — 대신 헬스체크 라우트를 `Limiter`가 등록되지 않은 별도 `APIRouter`에 두거나, `exempt_when` 콜백으로 조건부 예외 처리한다.

```python
# main.py — /health/* 는 전역 미들웨어보다 먼저 짧게 반환하거나, exempt 콜백으로 제외
limiter = Limiter(
    key_func=get_remote_address,
    default_limits=["100/minute"],
)

@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}   # 이 라우트에 @limiter.limit()을 붙이지 않으면 default_limits만 적용됨에 주의
```

`default_limits`는 `@limiter.limit()`이 없는 라우트에도 전역 미들웨어를 통해 적용되므로, 완전히 제외하려면 `request.state.view_rate_limit`을 조작하는 커스텀 로직이 필요하다 — 이 저장소 규모에서는 헬스체크 응답 시간이 매우 짧아 굳이 예외 처리하지 않고 넉넉한 `default_limits`로 흡수하는 것도 실용적인 선택이다.

### 응답 헤더

`slowapi`는 `SlowAPIMiddleware` 등록 시 자동으로 표준 헤더를 채운다 — root 원칙([conventions.md](../../../../docs/conventions.md))이 요구하는 것과 동일한 헤더다.

| 헤더 | 설명 |
|------|------|
| `X-RateLimit-Limit` | 허용된 최대 요청 수 |
| `X-RateLimit-Remaining` | 남은 요청 수 |
| `X-RateLimit-Reset` | 제한 초기화까지 남은 시간(초) |

제한 초과 시 `slowapi`는 `429 Too Many Requests`를 반환한다.

## 이 저장소의 미들웨어 체인에서의 위치

[cross-cutting-concerns.md](cross-cutting-concerns.md)가 정리한 요청 파이프라인에 Rate Limiting을 추가하면 다음과 같은 순서가 된다 — Correlation ID 주입보다도 앞서서 제한 초과 요청을 조기에 차단하는 것이 리소스 낭비를 줄인다.

```
요청 → [0. SlowAPIMiddleware — 제한 초과 시 즉시 429] → [1. Correlation ID Middleware] → [2. Depends(인증)] → [3. Pydantic 검증] → [4. 라우트 함수] → 응답
```

## 환경별 설정값 관리

```python
# src/config/rate_limit_config.py (신설 제안) — config.md의 관심사별 설정 클래스 패턴을 따름
import os


class RateLimitConfig:
    def __init__(self) -> None:
        self.default_limit = os.getenv("RATE_LIMIT_DEFAULT", "100/minute")
        self.write_limit = os.getenv("RATE_LIMIT_WRITE", "20/minute")
```

하드코딩된 문자열 리터럴(`"5/minute"`) 대신 위와 같이 환경 변수로 분리하면 스테이징/프로덕션에서 값을 조정할 수 있다 — [config.md](config.md)의 fail-fast 검증 패턴과 함께 적용한다.

## 원칙

- **전역 미들웨어로 기본값 등록**: `SlowAPIMiddleware` + `default_limits`로 모든 엔드포인트에 최소한의 제한을 건다.
- **쓰기 API를 더 엄격하게**: `POST`/`PUT`/`DELETE` 라우트는 `GET`보다 낮은 한도를 `@limiter.limit()`으로 개별 지정한다.
- **인증 도입 후에는 IP가 아닌 사용자 ID 기준으로 전환**: NAT 뒤 여러 사용자가 IP를 공유하는 문제를 피한다.
- **제한값은 환경 변수로 관리**: 하드코딩하지 않고 `config/` 클래스로 분리한다.
- **아직 미구현임을 숨기지 않는다**: 이 문서는 forward-looking 가이드이며, `requirements.txt`에 `slowapi`를 추가하고 위 코드를 반영하는 것이 실제 후속 작업이다.

---

### 관련 문서

- [conventions.md](../../../../docs/conventions.md) — Rate Limiting 언어 무관 원칙 (root)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 체인 내 위치
- [authentication.md](authentication.md) — 사용자 ID 기준 제한으로 전환하기 위한 전제 조건
- [config.md](config.md) — 환경별 제한값 관리
