# 에러 처리 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/error-handling.md](../../../../docs/architecture/error-handling.md)

## 현재 구현

`src/account/domain/errors.py`가 예외 계층을 정의하고 있다.

```python
# src/account/domain/errors.py
class AccountError(Exception):
    pass


class AccountNotFoundError(AccountError):
    def __init__(self, account_id: str) -> None:
        super().__init__(f"account not found: {account_id}")
        self.account_id = account_id


class InvalidAmountError(AccountError):
    def __init__(self) -> None:
        super().__init__("금액은 0보다 커야 합니다.")


class DepositRequiresActiveAccountError(AccountError):
    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 입금할 수 있습니다.")
```

`main.py`에서 두 단계로 매핑한다.

```python
# main.py
@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
```

**레이어 분리 자체는 올바르다:** Domain(`errors.py`)은 plain `Exception`만 던지고 HTTP를 모른다. HTTP 상태 코드 변환은 `main.py`(Interface 레이어)에만 있다. `AccountNotFoundError`를 `AccountError`보다 먼저 등록해 404를 400보다 우선 매칭시키는 것도 정확하다.

---

## 알려진 격차 — 에러 코드(`code`)가 없고 응답 형식이 root 표준과 다르다

root 원칙은 모든 에러 응답에 `statusCode`, `code`, `message`, `error` 네 필드를 요구하지만, 현재 응답은 `{"message": "..."}`뿐이다.

```json
// 현재 응답 — 격차
{ "message": "account not found: acc-123" }

// root 표준 형식
{
  "statusCode": 404,
  "code": "ACCOUNT_NOT_FOUND",
  "message": "계좌를 찾을 수 없습니다.",
  "error": "Not Found"
}
```

`code`가 없으면 클라이언트가 에러 메시지 텍스트(자연어, 번역 가능)로 분기해야 한다 — 이는 root 문서가 명시적으로 금지하는 패턴이다.

---

## 올바른 패턴 — 에러 코드 enum + 표준 응답

### 1. 에러 코드 enum 추가

```python
# src/account/domain/error_codes.py (신설 제안)
from enum import Enum


class AccountErrorCode(str, Enum):
    ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    INVALID_AMOUNT = "INVALID_AMOUNT"
    DEPOSIT_REQUIRES_ACTIVE_ACCOUNT = "DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"
    WITHDRAW_REQUIRES_ACTIVE_ACCOUNT = "WITHDRAW_REQUIRES_ACTIVE_ACCOUNT"
    INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    SUSPEND_REQUIRES_ACTIVE_ACCOUNT = "SUSPEND_REQUIRES_ACTIVE_ACCOUNT"
    REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT = "REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT"
    ACCOUNT_ALREADY_CLOSED = "ACCOUNT_ALREADY_CLOSED"
    ACCOUNT_BALANCE_NOT_ZERO = "ACCOUNT_BALANCE_NOT_ZERO"
    VALIDATION_FAILED = "VALIDATION_FAILED"   # Pydantic 검증 실패 전용, 고정값
```

각 예외 클래스가 자신의 코드를 속성으로 갖도록 `errors.py`를 확장한다.

```python
# src/account/domain/errors.py — 수정 제안
from .error_codes import AccountErrorCode


class AccountError(Exception):
    code: AccountErrorCode


class AccountNotFoundError(AccountError):
    code = AccountErrorCode.ACCOUNT_NOT_FOUND

    def __init__(self, account_id: str) -> None:
        super().__init__(f"계좌를 찾을 수 없습니다: {account_id}")
        self.account_id = account_id


class InsufficientBalanceError(AccountError):
    code = AccountErrorCode.INSUFFICIENT_BALANCE

    def __init__(self) -> None:
        super().__init__("잔액이 부족합니다.")
```

### 2. 표준 에러 응답 스키마

```python
# src/common/error_response.py (신설 제안)
from pydantic import BaseModel


class ErrorResponse(BaseModel):
    statusCode: int
    code: str
    message: str
    error: str


_STATUS_TEXT = {400: "Bad Request", 404: "Not Found", 422: "Unprocessable Entity", 500: "Internal Server Error"}


def build_error_response(status_code: int, code: str, message: str) -> dict:
    return ErrorResponse(
        statusCode=status_code, code=code, message=message, error=_STATUS_TEXT.get(status_code, "Error")
    ).model_dump()
```

### 3. `main.py`에서 통일된 핸들러로 매핑

```python
# main.py — 수정 제안
from src.account.domain.errors import AccountError, AccountNotFoundError
from src.common.error_response import build_error_response

_STATUS_BY_ERROR_TYPE = {
    AccountNotFoundError: 404,
    # 다른 구체 예외 타입은 기본 400으로 처리 (아래 account_error_handler)
}


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))
```

### 4. Pydantic 검증 실패(422) — `code`는 `VALIDATION_FAILED` 고정

FastAPI 기본 422 응답도 같은 형식으로 통일한다.

```python
# main.py
from fastapi.exceptions import RequestValidationError


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    messages = [str(e["msg"]) for e in exc.errors()]
    return JSONResponse(
        status_code=422,
        content=build_error_response(422, "VALIDATION_FAILED", "; ".join(messages)),
    )
```

### 5. 매핑되지 않은 예외 — 500

`main.py`에 등록되지 않은 예외 타입은 FastAPI 기본 처리로 500이 반환된다. 프로덕션에서는 스택 트레이스를 응답 본문에 노출하지 않도록 `debug=False`(FastAPI 기본값)를 유지하고, [observability.md](observability.md)의 구조화 로깅으로 서버 측에만 상세 정보를 남긴다.

---

## 원칙

- **Domain/Application은 plain Exception만 throw**: `errors.py`는 HTTP를 모른다. 이미 이 원칙은 지켜지고 있다.
- **모든 예외에 고유 코드를 부여**: 메시지 텍스트가 아닌 `code`로 클라이언트가 분기하도록 한다.
- **에러 응답은 4개 필드 고정**: `statusCode`, `code`, `message`, `error`.
- **에러 변환은 `main.py`(Interface 레이어)에서만**: `@app.exception_handler`가 유일한 변환 지점이다.
- **Validation 실패는 `code: VALIDATION_FAILED`로 고정**: 비즈니스 에러 코드와 구분한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부 에러 throw 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [observability.md](observability.md) — 에러 로깅
