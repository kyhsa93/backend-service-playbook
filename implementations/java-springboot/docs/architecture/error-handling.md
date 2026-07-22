# Error Handling (Spring Boot)

> For the framework-agnostic principles, see the root [error-handling.md](../../../../docs/architecture/error-handling.md).

## Error handling per layer — the current implementation matches the principle

| Layer | Handling approach | This repository's implementation |
|---|---|---|
| Domain | throws a typed exception | `AccountException(ErrorCode, message)` |
| Application | propagates as-is (never caught) | Command/Query Services never catch the exception |
| Interface | converts exception → HTTP response | `AccountController.handleAccountException` (`@ExceptionHandler`) |

```java
// account/domain/AccountException.java — actual code
public class AccountException extends RuntimeException {

    public enum ErrorCode {
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INVALID_MONEY_AMOUNT,
        CURRENCY_MISMATCH,
        DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
        WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
        INSUFFICIENT_BALANCE,
        SUSPEND_REQUIRES_ACTIVE_ACCOUNT,
        REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT,
        ACCOUNT_ALREADY_CLOSED,
        ACCOUNT_BALANCE_NOT_ZERO
    }

    private final ErrorCode code;

    public AccountException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

- A Domain method (`Account.deposit()`, etc.) throws `AccountException` immediately on an invariant violation — this matches the root's principle that "Domain must throw only a plain Error/typed exception" (extending `RuntimeException` is the Java equivalent of a "plain Error").
- **Because `ErrorCode` is an enum, typos are caught at compile time** — the effect the root achieves in TypeScript via the enum key=value trick (forcing messages to be written only through `OrderErrorMessage['...']`) is achieved naturally in Java through enum constant references (`ErrorCode.INSUFFICIENT_BALANCE`). A code can never be expressed as a free-form string.
- The message (`"Insufficient balance."`) is passed directly as a constructor argument each time — unlike the root's `<Domain>ErrorMessage` enum, the message itself is not typed. Given that each `ErrorCode` always maps to a fixed message (which is true of the current code), embedding the message inside `ErrorCode` would align more closely with the root principle (typing error messages as an enum too) — see "Possible improvement" below.

```java
// interfaces/rest/AccountController.java — actual code
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
}
```

That the point where `@ExceptionHandler` catches `AccountException` and converts it into an HTTP status code exists only in the Interface layer (the Controller) also matches the root principle.

---

## Error response format — 4 fields, matching the root format

Root principle: every error response has the 4 fields `statusCode`/`code`/`message`/`error`.

```java
// interfaces/rest/ErrorResponse.java — actual code
public record ErrorResponse(int statusCode, String code, String message, String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
```

Both `statusCode` (the numeric HTTP status code) and `error` (the HTTP status text, e.g. `"Not Found"`) are included in the response body, so a client can confirm the HTTP status from the response body alone.

```java
// AccountController — actual code
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
}
```

Example response:

```json
{
  "statusCode": 404,
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account not found.",
  "error": "Not Found"
}
```

**Room for improvement**: `AccountControllerE2ETest` currently only verifies `response.getBody().get("code")` and doesn't separately assert the `statusCode`/`error` field values — since all 4 fields are already populated in the response body itself, there's no regression risk, but an assertion like `assertThat(body.get("statusCode")).isEqualTo(404)` could be added to also pin down conformance to the root format explicitly via tests.

---

## Validation failure responses — handled by the global handler

If `@Valid @RequestBody CreateAccountRequest request` fails Bean Validation, Spring throws a `MethodArgumentNotValidException`. `common/web/GlobalExceptionHandler` (`@RestControllerAdvice`) handles it and responds with the same 4-field `ErrorResponse` schema as `AccountException`.

```java
// common/web/GlobalExceptionHandler.java — actual code
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> messages = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "VALIDATION_FAILED", String.join(", ", messages), "Bad Request"));
    }
}
```

Root principle: a validation failure's `code` is always the fixed value `VALIDATION_FAILED`. Since there can be multiple field errors, `message` may also be an array of strings (see the root's example) — this repository simplifies it to a single joined string.

**Why `@ExceptionHandler` lives on a global `@RestControllerAdvice` class rather than on `AccountController`**: as domains grow in number, each Controller would end up redefining its own `MethodArgumentNotValidException` handler. A domain-agnostic common exception like validation is handled once, globally, while only domain-specific exceptions like `AccountException` are left in their respective Controllers (`AccountController`/`CardController`/`AuthController`) — the same placement principle as the rate-limit (`RequestNotPermitted`) handler.

---

## Harness verification

- `harness/src/rules/ErrorResponseSchema.java` (rule: `error-response-schema`) dynamically finds the generic type of the `ResponseEntity<Xxx>` returned by `GlobalExceptionHandler`'s `@ExceptionHandler` methods, and checks that it has exactly the 4 fields `statusCode` (number)/`code` (String)/`message` (String or array)/`error` (String) — more or fewer fields, or misspelled field names, fail the check.
- `harness/src/rules/TypedErrorsOnly.java` (rule: `typed-errors-only`) fails the build if `domain/`·`application/` throws a generic exception directly with a string, such as `throw new RuntimeException(...)` — this enforces the root's absolute principle (AGENTS.md "errors are typed as enums — free-form strings are forbidden") that only domain-specific typed exceptions (carrying an `ErrorCode` enum) are allowed.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the pattern of throwing exceptions inside an Aggregate
- [layer-architecture.md](layer-architecture.md) — the separation of error-handling responsibility per layer
- [testing.md](testing.md) — verifying error response format in E2E tests
