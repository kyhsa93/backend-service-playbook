# Error Handling Pattern

> Framework-agnostic principles: [../../../../docs/architecture/error-handling.md](../../../../docs/architecture/error-handling.md)

## Current implementation

`src/account/domain/errors.py` defines the exception hierarchy, and `src/account/domain/error_codes.py` defines the unique code corresponding to each exception as the `AccountErrorCode` enum.

```python
# src/account/domain/error_codes.py
from enum import Enum


class AccountErrorCode(str, Enum):
    ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    INVALID_AMOUNT = "INVALID_AMOUNT"
    DEPOSIT_REQUIRES_ACTIVE_ACCOUNT = "DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"
    # ... a code exists 1:1 for every exception class in errors.py
    VALIDATION_FAILED = "VALIDATION_FAILED"   # reserved exclusively for Pydantic validation failures, a fixed value
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
        super().__init__("The amount must be greater than 0.")
```

`src/card/domain/error_codes.py` + `src/card/domain/errors.py` follow the same pattern — since Account and Card are each their own Bounded Context, their error code enums are also defined independently per domain (not shared).

`main.py` maps this in two stages, assembling the standard shape with a shared response builder.

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

**The layer separation itself is correct:** the Domain layer (`errors.py`, `error_codes.py`) only throws plain `Exception`/`Enum` and knows nothing about HTTP. HTTP status-code conversion and standard-response assembly live only in `main.py` (the Interface layer) and `src/common/error_response.py`. Registering `AccountNotFoundError` before `AccountError` so 404 matches before 400 is also correct. The Card domain likewise registers `CardNotFoundError`/`LinkedAccountNotFoundError` before `CardError` to keep the same ordering.

---

## Standard error response schema

Every error response has the four fields required by the root principle: `statusCode`, `code`, `message`, `error`.

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
// actual response
{
  "statusCode": 404,
  "code": "ACCOUNT_NOT_FOUND",
  "message": "account not found: acc-123",
  "error": "Not Found"
}
```

Because `code` is present, the client can branch on the stable `code` rather than on the message text (natural language, translatable).

---

## Pydantic validation failures (422) — `code` is fixed to `VALIDATION_FAILED`

FastAPI's default 422 response is also unified into the same shape.

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

## Unmapped exceptions — 500

Any exception type not registered in `main.py` results in a 500 via FastAPI's default handling. In production, keep `debug=False` (FastAPI's default) so a stack trace is never exposed in the response body, and use the structured logging from [observability.md](observability.md) to leave detailed information only on the server side.

---

## Principles

- **Domain/Application only throw plain Exceptions**: `errors.py` knows nothing about HTTP.
- **Every exception gets a unique code**: the client branches on `code`, not on message text. The enum in `error_codes.py` corresponds 1:1 with every exception class in `errors.py`.
- **The error response has a fixed set of 4 fields**: `statusCode`, `code`, `message`, `error` — `build_error_response()` in `src/common/error_response.py` is the sole assembly point.
- **Error conversion happens only in `main.py` (the Interface layer)**: `@app.exception_handler` is the sole conversion point.
- **Validation failures are fixed to `code: VALIDATION_FAILED`**: distinguished from business error codes.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the pattern for throwing errors inside an Aggregate
- [layer-architecture.md](layer-architecture.md) — separation of responsibilities per layer
- [observability.md](observability.md) — error logging

---

The harness's `error-response-schema` rule (`../../harness/rules/error_response_schema.py`) verifies that the field set of the response body assembled by `@app.exception_handler(...)` matches exactly the 4 fields `statusCode`/`code`/`message`/`error` (no more, no fewer), and the `typed-errors-only` rule (`../../harness/rules/typed_errors_only.py`) verifies that `domain/`/`application/` raise typed exception classes instead of free-form string exceptions such as `raise Exception("...")`.
