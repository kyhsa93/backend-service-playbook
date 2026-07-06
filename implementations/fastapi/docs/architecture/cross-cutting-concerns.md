# 횡단 관심사 (Cross-Cutting Concerns)

> 프레임워크 무관 원칙: [../../../../docs/architecture/cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md)

## 알려진 격차 — 현재 미들웨어가 전혀 없다

`main.py`에는 `@app.exception_handler`(에러 변환)만 있고, 요청 파이프라인 전처리(Correlation ID, 로깅) 미들웨어가 없다. 인증도 `Header(...)`로 `X-User-Id`를 그대로 신뢰한다 — [authentication.md](authentication.md)의 알려진 격차 참조. 아래는 FastAPI의 미들웨어/`Depends` 메커니즘을 이용한 올바른 파이프라인 구성이다.

---

## 요청 파이프라인 — FastAPI에서의 대응

FastAPI에는 NestJS의 Guard/Pipe/Interceptor 같은 별도 계층이 없다. 대신 **`Middleware`**(모든 요청 공통 전처리/후처리)와 **`Depends`**(라우트별 의존성 주입 — 인증, 검증)로 동일한 역할을 나눈다.

```
요청 → [1. Middleware] → [2. Depends(인증)] → [3. Pydantic 요청 모델 검증] → [4. 라우트 함수] → [5. Middleware 후처리] → 응답
```

| 단계 | FastAPI 메커니즘 | 역할 |
|------|-----------------|------|
| 1. 전처리 | `@app.middleware("http")` | 모든 요청에 Correlation ID 주입 |
| 2. 인증 | `Depends(get_current_user)` | 토큰 검증, 사용자 정보 추출 |
| 3. 입력 검증 | Pydantic 요청 모델 (자동) | 타입/필수값 검증 — FastAPI가 자동으로 422 반환 |
| 4. 라우트 함수 | `interface/rest/*_router.py` | Handler 호출, 에러 전파 |
| 5. 후처리 | 같은 미들웨어 함수 내 `response` 이후 코드 | 요청 로깅, 응답 시간 측정 |

---

## Correlation ID — `contextvars` + 미들웨어

Node의 `AsyncLocalStorage`에 대응하는 Python 표준 라이브러리는 `contextvars`다. 요청 진입 시 미들웨어에서 설정하면, 이후 모든 async 호출 체인에서 함수 인자 없이 접근할 수 있다.

```python
# src/common/correlation.py (신설 제안)
from contextvars import ContextVar
import uuid

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"


def set_correlation_id(value: str) -> None:
    _correlation_id.set(value)


def generate_correlation_id() -> str:
    return uuid.uuid4().hex
```

```python
# main.py — 미들웨어로 주입
import time

from fastapi import Request

from src.common.correlation import generate_correlation_id, set_correlation_id, get_correlation_id


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
        extra={"duration_ms": round(duration_ms, 1), "correlation_id": correlation_id},
    )
    return response
```

`contextvars.ContextVar`는 `asyncio` 태스크마다 독립된 컨텍스트를 가지므로, 동시에 처리되는 여러 요청의 Correlation ID가 서로 섞이지 않는다. 이후 로깅(`observability.md`)에서 `get_correlation_id()`로 꺼내 모든 로그 라인에 포함시킨다.

---

## 인증 — `Depends`가 Guard 역할

인증은 Interface 레이어에서만 처리하고, Application/Domain은 인증 컨텍스트를 모른다는 원칙은 FastAPI에서도 동일하다. NestJS의 클래스 레벨 `@UseGuards(AuthGuard)`에 해당하는 것은 라우터 전체에 공통 `Depends`를 거는 것이다.

```python
# 올바른 방식 — 라우터 전체에 공통 의존성 적용 (개별 라우트에서 빠뜨릴 위험 없음)
router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(verify_no_op)])

@router.get("/{account_id}")
async def get_account(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),  # 라우트 함수 인자로 실제 사용자 정보 필요 시
    ...
) -> GetAccountResponse: ...
```

→ 토큰 검증 로직 자체는 [authentication.md](authentication.md) 참조 — 현재 `X-User-Id` 헤더를 그대로 신뢰하는 대신 JWT `Depends`로 교체해야 한다.

---

## 입력 검증 — Pydantic이 자동으로 처리

FastAPI는 요청 본문/쿼리 파라미터를 Pydantic 모델(`interface/rest/schemas.py`)로 선언하면 타입 불일치·필수값 누락 시 자동으로 `422 Unprocessable Entity`를 반환한다. 별도 Pipe 계층이 필요 없다.

```python
# src/account/interface/rest/schemas.py — 이미 이 패턴을 따르고 있다
class CreateAccountRequest(BaseModel):
    currency: str
    email: EmailStr   # 이메일 형식 검증도 Pydantic이 자동 수행
```

**형식 검증과 비즈니스 규칙을 혼동하지 않는다.** `email` 형식이 아니면 422(Pydantic), 이미 정지된 계좌에 입금하면 400(`domain/errors.py`의 `DepositRequiresActiveAccountError`) — 후자는 Application/Domain 레이어의 책임이다.

---

## HTTP 요청 로깅 — 미들웨어에서 일괄 처리

라우트 함수(`account_router.py`) 내부에서 개별적으로 로깅하지 않는다. 위 Correlation ID 미들웨어의 후처리 블록이 이미 요청 메서드/경로/처리시간을 로깅한다 — 로깅 위치를 한 곳으로 모으면 라우트 함수는 비즈니스 흐름만 남는다.

---

## Domain 레이어에서 미들웨어/로거 사용 금지

```python
# 금지 — Domain 레이어(account.py)에서 로거/contextvars 직접 사용
from src.common.correlation import get_correlation_id  # ← 금지

class Account:
    def deposit(self, amount: int) -> Transaction:
        logger.info(f"deposit called, correlation_id={get_correlation_id()}")  # ← 금지
        ...
```

`src/account/domain/account.py`는 현재 이 원칙을 잘 지키고 있다 — 어떤 로깅도, 프레임워크 import도 없다. 새 도메인 메서드를 추가할 때도 이 순수성을 유지한다.

---

## 원칙

- **역할에 맞는 FastAPI 메커니즘 사용**: 전역 전처리는 `@app.middleware`, 라우트별 인증/의존성은 `Depends`, 입력 검증은 Pydantic 모델.
- **전처리는 미들웨어에서 가장 먼저**: Correlation ID 주입은 다른 모든 처리보다 앞서 실행되어야 한다.
- **라우트 함수는 순수하게**: Handler 호출과 응답 변환만 담당한다. 로깅/인증 로직을 라우트 함수 안에 직접 작성하지 않는다.
- **공통 의존성은 라우터 레벨**: `APIRouter(dependencies=[...])`로 걸어 개별 라우트 누락을 방지한다.

---

### 관련 문서

- [authentication.md](authentication.md) — JWT 인증 상세
- [observability.md](observability.md) — Correlation ID를 포함한 구조화 로깅
- [error-handling.md](error-handling.md) — 에러 변환 위치(`@app.exception_handler`)
