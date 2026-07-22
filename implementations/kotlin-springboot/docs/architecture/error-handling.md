# Error Handling — Kotlin Spring Boot

> For the framework-agnostic principles, see [root error-handling.md](../../../../docs/architecture/error-handling.md).

## Current implementation — the sealed class exception hierarchy

```kotlin
// domain/AccountException.kt — actual code
sealed class AccountException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : AccountException("account not found: $accountId")
class InvalidAmountException : AccountException("The amount must be greater than 0.")
class InsufficientBalanceException : AccountException("Insufficient balance.")
// ...
```

`sealed class` expresses the root's "plain Error + typed error messages" principle in a Kotlin-native way — since subclassing is only possible within the same module (file), the compiler knows every subtype, and branching with `when (exception) { ... }` catches a missed-handling case at compile time when a new exception is added. The harness's `sealed-exception` check verifies that this hierarchy lives in `domain/`.

Each subclass now has a `code` property that maps 1:1 to the error code (`AccountErrorCode`) the root requires — the actual structure is explained below.

---

## Response format — the `ErrorResponse` 4 fields

```kotlin
// interfaces/rest/Schemas.kt — actual code
data class ErrorResponse(
    val statusCode: Int,
    val code: String,
    val message: String,
    val error: String,
)
```

This implements the root-required `{ statusCode, code, message, error }` 4-field structure exactly. A client can branch on `code`, and while `AccountNotFoundException` (404) and every other `AccountException` (400) still split into two groups — the `code` field distinguishes the precise cause even within the same 400, e.g. `InsufficientBalanceException` (`INSUFFICIENT_BALANCE`) vs. `AccountAlreadyClosedException` (`ACCOUNT_ALREADY_CLOSED`). The reason the cause is distinguished by `code` instead of subdividing the HTTP status code further (e.g. 409 CONFLICT) is that the existing E2E tests (`AccountControllerE2ETest`) already verify all of these exceptions as 400, so subdividing the status code would break backward compatibility — this matches the root principle that HTTP status is the "category" and `code` is the "precise cause."

## Error codes — an enum mapping 1:1 to exception types

```kotlin
// domain/AccountErrorCode.kt — actual code
enum class AccountErrorCode {
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
    ACCOUNT_BALANCE_NOT_ZERO,
    DELETE_REQUIRES_CLOSED_ACCOUNT,
}
```

Each subclass of `AccountException` has its code as a property — since it's a `sealed class`, forgetting to include `code` when adding a new subtype surfaces at compile time.

```kotlin
// domain/AccountException.kt — actual code
sealed class AccountException(message: String, val code: AccountErrorCode) : RuntimeException(message)

class AccountNotFoundException(accountId: String) :
    AccountException("account not found: $accountId", AccountErrorCode.ACCOUNT_NOT_FOUND)

class InsufficientBalanceException :
    AccountException("Insufficient balance.", AccountErrorCode.INSUFFICIENT_BALANCE)

class AccountAlreadyClosedException :
    AccountException("This account is already closed.", AccountErrorCode.ACCOUNT_ALREADY_CLOSED)
// ...
```

`httpStatus` exists only in the Interface layer's (`GlobalExceptionHandler`'s) mapping, not on the domain exception — the domain/application layers know only `code` and have no HTTP dependency at all. This is **adopting the stricter of the two alternatives** error-handling.md presents (① putting `httpStatus` on the domain exception, ② splitting it out into an Interface-layer mapping table) — option ②.

---

## Interface layer — global handling via `@RestControllerAdvice`

Instead of scattering `@ExceptionHandler`s across each Controller, they're handled once, application-wide, via `@RestControllerAdvice`. `AccountController` has no `@ExceptionHandler` methods.

```kotlin
// common/GlobalExceptionHandler.kt — actual code
package com.example.accountservice.common

import com.example.accountservice.account.domain.AccountException
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.interfaces.rest.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Account not found: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> {
        logger.warn("Account request failed: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected error", e)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.")
    }

    private fun errorResponse(status: HttpStatus, code: String, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(status.value(), code, message, status.reasonPhrase))
}
```

The dedicated handler for `AccountNotFoundException` is declared before the handler for `AccountException` (its parent), but the order doesn't matter — Spring's `@ExceptionHandler` matching picks the most specific type in the exception hierarchy, not declaration order. `AccountController` has had its two `@ExceptionHandler` methods removed, and adding a new Controller (for a future domain) reuses this same global handler — the benefit is that thanks to the `sealed class` hierarchy, exception matching automatically covers every subtype just by catching `AccountException` (the parent) alone (no handler-code changes needed when adding a new subtype exception).

**Catching unclassified exceptions (`Exception::class`) last and responding with 500 is essential** — otherwise an unexpected exception gets exposed via Spring's default error page (HTML), breaking the contract an API client expects (JSON).

---

## Validation failure response example

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": "email: must be a well-formed email address",
  "error": "Bad Request"
}
```

---

## Principle summary

- **The `sealed class` exception hierarchy is kept** — already a Kotlin-native implementation aligned with the root principle.
- **The `AccountErrorCode` enum has been added** — mapped 1:1 with each exception.
- **`ErrorResponse` has been extended to 4 fields** (`statusCode`/`code`/`message`/`error`).
- **Error conversion is globalized via `@RestControllerAdvice`** — `AccountController` has no `@ExceptionHandler`; everything is consolidated into a single `common/GlobalExceptionHandler.kt`.
- **Unclassified exceptions are caught-all as 500** — implemented via `handleUnknown(Exception::class)`.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — throwing exceptions inside the Aggregate
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where in the pipeline error conversion happens
- harness `error-response-schema`/`typed-errors-only` rules (`../../harness/README.md`) — mechanically verifies that `ErrorResponse` has exactly 4 fields, and that domain/application only uses typed sealed class exceptions rather than free-form string exceptions like `RuntimeException("...")`
