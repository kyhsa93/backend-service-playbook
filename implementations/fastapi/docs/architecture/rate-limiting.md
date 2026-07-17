# Rate Limiting

> Rate Limiting의 언어 무관 원칙(전역 기본값, 쓰기/읽기 차등, 내부 엔드포인트 제외, 응답 헤더)은 root [conventions.md](../../../../docs/conventions.md) "4. Rate Limiting 원칙" 절을 참조한다. 이 문서는 그 원칙을 FastAPI로 구현하는 방법을 다룬다.

## 구현 현황 — `slowapi`로 구현되어 있다

`examples/requirements.txt`에 `slowapi`가 추가되어 있고, `examples/src/common/rate_limit.py`(`Limiter` 인스턴스), `examples/src/config/rate_limit_config.py`(환경 변수 기반 제한값), `examples/main.py`(전역 미들웨어/예외 핸들러 등록), `examples/src/account/interface/rest/account_router.py`(쓰기 엔드포인트별 `@limiter.limit()`)에 아래 문서 내용이 그대로 반영되어 있다.

---

## `slowapi` — Starlette 기반, FastAPI에 가장 관용적인 선택

FastAPI는 Starlette 위에서 동작하므로, NestJS의 `@nestjs/throttler`처럼 프레임워크가 미들웨어 등록 지점(`app.add_middleware`)과 예외 처리 지점(`@app.exception_handler`)을 이미 제공한다. `slowapi`는 이 두 지점에 얹는 얇은 래퍼로, Redis 없이 in-memory로도 동작해 로컬 개발과 잘 맞는다.

```bash
pip install slowapi
```

### 전역 등록 — `src/common/rate_limit.py` + `main.py`

`Limiter` 인스턴스를 `main.py`에 직접 두면 `account_router.py`가 `@limiter.limit(...)`을 쓰기 위해 `main`을 import해야 해서 순환 import가 발생한다([module-pattern.md](module-pattern.md) "Python의 순환 참조" 절 참고) — `main.py`는 이미 `account_router`를 import하고 있기 때문이다. 그래서 `Limiter` 싱글턴은 공유 인프라 위치인 `src/common/rate_limit.py`에 두고, `main.py`와 각 라우터가 둘 다 거기서 import한다.

```python
# src/common/rate_limit.py — 실제 코드
from slowapi import Limiter
from slowapi.util import get_remote_address

from ..config.rate_limit_config import RateLimitConfig

rate_limit_config = RateLimitConfig()
limiter = Limiter(key_func=get_remote_address, default_limits=[rate_limit_config.default_limit])
```

```python
# main.py — 실제 코드, 전역 등록
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from src.common.rate_limit import limiter

app = FastAPI(title="Account Service", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.include_router(auth_router)
app.include_router(account_router)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    ...


# Correlation ID Middleware 등록 "이후"에 추가해야 한다 — Starlette는 나중에 등록된
# 미들웨어일수록 바깥쪽 레이어가 되므로(가장 나중에 추가한 것이 요청을 가장 먼저 받는다),
# 여기서 추가해야 SlowAPIMiddleware가 Correlation ID 부여보다 앞서 요청을 가로채
# 제한 초과 시 조기에 429를 반환한다.
app.add_middleware(SlowAPIMiddleware)
```

`key_func=get_remote_address`는 클라이언트 IP 기준으로 제한한다. 이 저장소는 이미 JWT 인증([authentication.md](authentication.md))이 도입되어 있으므로 원칙적으로는 `Depends(get_current_user)`로 얻은 사용자 ID 기준으로 제한하는 것이 IP보다 정확하다(IP는 NAT/프록시 뒤에서 여러 사용자가 공유할 수 있다). 다만 `slowapi`의 `key_func`는 `Request`만 받고 FastAPI의 `Depends` 결과에는 접근하지 못하므로, 사용자 ID 기준으로 바꾸려면 `key_func` 내부에서 `Authorization: Bearer` 헤더를 직접 파싱해 `JwtAuthService().verify_token(...)`을 호출해야 한다 — 이는 인증 검증 로직이 두 곳(라우터의 `Depends(get_current_user)`와 rate limit의 `key_func`)에 중복되는 트레이드오프가 있어, 이 저장소 규모에서는 `get_remote_address`를 그대로 쓰는 실용적인 선택을 했다. 트래픽이 늘어 IP 공유 문제가 실제로 불거지면 아래 방향으로 전환한다.

```python
# 향후 전환 방향 — 사용자 ID 기준 제한(아직 적용하지 않음, 위 트레이드오프 참고)
from src.auth.domain.errors import InvalidTokenError
from src.auth.infrastructure.jwt_auth_service import JwtAuthService


def _rate_limit_key(request: Request) -> str:
    auth_header = request.headers.get("authorization", "")
    if auth_header.lower().startswith("bearer "):
        try:
            return JwtAuthService().verify_token(auth_header.split(" ", 1)[1])
        except InvalidTokenError:
            pass
    return get_remote_address(request)
```

### 엔드포인트별 제한 — 데코레이터

`@router.post`보다 `@limiter.limit(...)`를 **먼저**(데코레이터는 아래에서 위로 적용되므로 실질적으로는 바깥쪽에) 선언해야 한다. `slowapi`는 라우트 함수 시그니처에 `request: Request` 파라미터가 있어야 동작한다.

```python
# src/account/interface/rest/account_router.py — 실제 코드
from fastapi import APIRouter, Depends, Request

from ....common.rate_limit import limiter, rate_limit_config

@router.post("/{account_id}/withdraw", status_code=201, response_model=TransactionResponse)
@limiter.limit(rate_limit_config.write_limit)   # 쓰기 API는 읽기보다 엄격하게 (conventions.md 원칙)
async def withdraw(
    request: Request,   # slowapi가 이 파라미터로 클라이언트를 식별
    account_id: str,
    body: WithdrawRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    ...


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(account_id: str, ...) -> GetAccountResponse:
    ...   # 별도 데코레이터 없음 — 전역 default_limits(RATE_LIMIT_DEFAULT)만 적용된다
```

`create_account`/`deposit`/`withdraw`/`suspend_account`/`reactivate_account`/`close_account`(모두 `POST`) 6개 쓰기 엔드포인트에 `@limiter.limit(rate_limit_config.write_limit)`이 적용되어 있다. `get_account`/`get_transactions`(모두 `GET`) 조회 엔드포인트는 별도 데코레이터 없이 `Limiter(default_limits=...)`를 통해 전역 기본값만 적용받는다 — 조회를 쓰기보다 관대하게 두는 "원칙"은 지키면서도, `config/rate_limit_config.py`가 read 전용 값을 별도로 노출하지는 않는다(필요해지면 `RateLimitConfig`에 `read_limit` 필드를 추가해 확장한다).

`auth_router.py`의 `sign_up`/`sign_in`도 `POST`이므로 동일하게 `@limiter.limit(rate_limit_config.write_limit)`이 적용되어 있다(issue #193) — 특히 `sign_in`은 비밀번호 검증이 도입된 뒤([authentication.md](authentication.md)) 브루트포스 공격의 표적이 될 수 있는 엔드포인트라, 전역 기본값(`RATE_LIMIT_DEFAULT`)보다 엄격한 쓰기 등급 제한을 받아야 한다.

### 내부/헬스체크 엔드포인트 제외

`slowapi`에는 NestJS의 `@SkipThrottle()` 같은 전용 데코레이터가 없다 — 대신 헬스체크 라우트를 `Limiter`가 등록되지 않은 별도 `APIRouter`에 두거나, `exempt_when` 콜백으로 조건부 예외 처리한다.

> 이 절은 아직 forward-looking이다 — `/health/live`, `/health/ready` 엔드포인트 자체가 이 저장소에 없다([graceful-shutdown.md](graceful-shutdown.md)에 이미 기록된 별개의 격차). 아래는 그 엔드포인트가 추가될 때 함께 반영할 가이드다.

```python
# main.py — /health/* 는 전역 미들웨어보다 먼저 짧게 반환하거나, exempt 콜백으로 제외
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

## 운영값 조정

```python
# src/config/rate_limit_config.py — 실제 코드. config.md의 관심사별 설정 클래스 패턴을 따름
import os


class RateLimitConfig:
    def __init__(self) -> None:
        self.default_limit = os.getenv("RATE_LIMIT_DEFAULT", "100/minute")
        self.write_limit = os.getenv("RATE_LIMIT_WRITE", "20/minute")
```

하드코딩된 문자열 리터럴 대신 위와 같이 환경 변수로 분리하면 스테이징/프로덕션에서 값을 조정할 수 있다 — [config.md](config.md)의 fail-fast 검증 패턴과 함께 적용한다. `RateLimitConfig()`는 `src/common/rate_limit.py`가 앱 시작 시 한 번 인스턴스화해 `Limiter(default_limits=[...])`와 `@limiter.limit(...)` 데코레이터에 값을 넘기므로, 값을 바꾸려면 코드 변경 없이 배포 환경에서 `RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE` 환경 변수를 설정하고 프로세스를 재기동하면 된다 — go(`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`)·nestjs(`THROTTLE_*`, [../../../nestjs/docs/architecture/rate-limiting.md](../../../nestjs/docs/architecture/rate-limiting.md) "운영값 조정" 참고)와 동일하게 배포 시점 설정을 지원한다(issue #153). `examples/tests/conftest.py`는 e2e 테스트가 같은 프로세스·같은 클라이언트 IP로 짧은 시간에 수십 건을 요청하는 상황을 피하기 위해 `RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE`를 넉넉한 값으로 override한다 — 운영 기본값(`100/minute`/`20/minute`)은 그대로 유지된다.

## 원칙

- **전역 미들웨어로 기본값 등록**: `SlowAPIMiddleware` + `default_limits`로 모든 엔드포인트에 최소한의 제한을 건다.
- **쓰기 API를 더 엄격하게**: `POST`/`PUT`/`DELETE` 라우트는 `GET`보다 낮은 한도를 `@limiter.limit()`으로 개별 지정한다.
- **사용자 ID 기준 전환은 트레이드오프를 감안해 판단한다**: IP는 NAT/프록시 뒤에서 여러 사용자가 공유할 수 있어 부정확하지만, `slowapi`의 `key_func`에서 사용자 ID를 얻으려면 JWT 검증 로직이 `Depends(get_current_user)`와 중복된다 — 이 저장소는 현재 규모에서 `get_remote_address`를 유지하는 실용적 선택을 했다(위 "전역 등록" 절 참고).
- **제한값은 환경 변수로 관리**: 하드코딩하지 않고 `config/` 클래스로 분리한다.

---

### 관련 문서

- [conventions.md](../../../../docs/conventions.md) — Rate Limiting 언어 무관 원칙 (root)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 체인 내 위치
- [authentication.md](authentication.md) — JWT 인증 흐름, 사용자 ID 기준 제한으로 전환할 때의 전제
- [module-pattern.md](module-pattern.md) — `Limiter`를 `common/`에 둬 순환 import를 피하는 이유
- [graceful-shutdown.md](graceful-shutdown.md) — 헬스체크 엔드포인트(아직 미구현) 도입 시 함께 반영할 rate limit 제외 처리
- [config.md](config.md) — 환경별 제한값 관리
