# 에러 처리 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/error-handling.md](../../../../docs/architecture/error-handling.md)

## 현재 구현

`src/account/domain/errors.py`가 예외 계층을 정의하고, `src/account/domain/error_codes.py`가 각 예외에 대응하는 고유 코드를 `AccountErrorCode` enum으로 정의한다.

```python
# src/account/domain/error_codes.py
from enum import Enum


class AccountErrorCode(str, Enum):
    ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    INVALID_AMOUNT = "INVALID_AMOUNT"
    DEPOSIT_REQUIRES_ACTIVE_ACCOUNT = "DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"
    # ... errors.py의 모든 예외 클래스에 대응하는 코드가 1:1로 존재
    VALIDATION_FAILED = "VALIDATION_FAILED"   # Pydantic 검증 실패 전용, 고정값
```

```python
# src/account/domain/errors.py
from .error_codes import AccountErrorCode


class AccountError(Exception):
    code: AccountErrorCode


class AccountNotFoundError(AccountError):
    code = AccountErrorCode.ACCOUNT_NOT_FOUND

    def __init__(self, account_id: str) -> None:
        super().__init__(f"account not found: {account_id}")
        self.account_id = account_id


class InvalidAmountError(AccountError):
    code = AccountErrorCode.INVALID_AMOUNT

    def __init__(self) -> None:
        super().__init__("금액은 0보다 커야 합니다.")
```

`src/card/domain/error_codes.py` + `src/card/domain/errors.py`도 동일한 패턴을 따른다 — Account와 Card는 각자의 Bounded Context이므로 에러 코드 enum도 도메인별로 독립적으로 정의한다(공유하지 않는다).

`main.py`에서 두 단계로 매핑하고, 공용 응답 빌더로 표준 형식을 조립한다.

```python
# main.py
from src.common.error_response import build_error_response


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))
```

**레이어 분리 자체는 올바르다:** Domain(`errors.py`, `error_codes.py`)은 plain `Exception`/`Enum`만 던지고 HTTP를 모른다. HTTP 상태 코드 변환과 표준 응답 조립은 `main.py`(Interface 레이어)와 `src/common/error_response.py`에만 있다. `AccountNotFoundError`를 `AccountError`보다 먼저 등록해 404를 400보다 우선 매칭시키는 것도 정확하다. Card 도메인도 `CardNotFoundError`/`LinkedAccountNotFoundError`를 `CardError`보다 먼저 등록해 동일한 순서를 지킨다.

---

## 표준 에러 응답 스키마

root 원칙이 요구하는 `statusCode`, `code`, `message`, `error` 네 필드를 모든 에러 응답이 갖는다.

```python
# src/common/error_response.py
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

```json
// 실제 응답
{
  "statusCode": 404,
  "code": "ACCOUNT_NOT_FOUND",
  "message": "account not found: acc-123",
  "error": "Not Found"
}
```

`code`가 있으므로 클라이언트는 메시지 텍스트(자연어, 번역 가능)가 아니라 안정적인 `code`로 분기할 수 있다.

---

## Pydantic 검증 실패(422) — `code`는 `VALIDATION_FAILED` 고정

FastAPI 기본 422 응답도 같은 형식으로 통일되어 있다.

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

---

## 매핑되지 않은 예외 — 500

`main.py`에 등록되지 않은 예외 타입은 FastAPI 기본 처리로 500이 반환된다. 프로덕션에서는 스택 트레이스를 응답 본문에 노출하지 않도록 `debug=False`(FastAPI 기본값)를 유지하고, [observability.md](observability.md)의 구조화 로깅으로 서버 측에만 상세 정보를 남긴다.

---

## 원칙

- **Domain/Application은 plain Exception만 throw**: `errors.py`는 HTTP를 모른다.
- **모든 예외에 고유 코드를 부여**: 메시지 텍스트가 아닌 `code`로 클라이언트가 분기하도록 한다. `error_codes.py`의 enum이 `errors.py`의 모든 예외 클래스와 1:1로 대응한다.
- **에러 응답은 4개 필드 고정**: `statusCode`, `code`, `message`, `error` — `src/common/error_response.py`의 `build_error_response()`가 유일한 조립 지점이다.
- **에러 변환은 `main.py`(Interface 레이어)에서만**: `@app.exception_handler`가 유일한 변환 지점이다.
- **Validation 실패는 `code: VALIDATION_FAILED`로 고정**: 비즈니스 에러 코드와 구분한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부 에러 throw 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [observability.md](observability.md) — 에러 로깅

---

harness `error-response-schema` 규칙(`../../harness/rules/error_response_schema.py`)이 `@app.exception_handler(...)`가 조립하는 응답 바디의 필드 집합이 `statusCode`/`code`/`message`/`error` 4개와 정확히 일치하는지(더 많지도 적지도 않게) 검증하고, `typed-errors-only` 규칙(`../../harness/rules/typed_errors_only.py`)이 `domain/`·`application/`에서 `raise Exception("...")` 같은 free-form 문자열 예외 대신 타입화된 예외 클래스를 raise하는지 검증한다.
