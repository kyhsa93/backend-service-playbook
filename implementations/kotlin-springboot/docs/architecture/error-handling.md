# 에러 처리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root error-handling.md](../../../../docs/architecture/error-handling.md) 참조.

## 현재 구현 — sealed class 예외 계층은 이미 올바르다

```kotlin
// domain/AccountException.kt — 실제 코드
sealed class AccountException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : AccountException("account not found: $accountId")
class InvalidAmountException : AccountException("금액은 0보다 커야 합니다.")
class InsufficientBalanceException : AccountException("잔액이 부족합니다.")
// ...
```

`sealed class`는 root의 "plain Error + 타입화된 에러 메시지" 원칙을 Kotlin다운 방식으로 표현한다 — 같은 모듈(파일) 내에서만 상속 가능하므로 컴파일러가 하위 타입 전체를 알고, `when (exception) { ... }`으로 분기하면 새 예외 추가 시 처리 누락을 컴파일 타임에 잡아낼 수 있다. harness의 `sealed-exception` 검사가 이 계층이 `domain/`에 있는지 확인한다.

**단, 현재 각 하위 클래스는 메시지만 있고 root가 요구하는 에러 코드(`AccountErrorCode`)와 1:1 매핑되지 않는다.** 아래에서 올바른 형태를 정의한다.

---

## 알려진 갭 — 응답 형식이 `message` 하나뿐이다

```kotlin
// interfaces/rest/Schemas.kt — 현재 코드
data class ErrorResponse(val message: String)
```

```kotlin
// interfaces/rest/AccountController.kt — 현재 코드
@ExceptionHandler(AccountNotFoundException::class)
fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: ""))

@ExceptionHandler(AccountException::class)
fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))
```

root가 요구하는 `{ statusCode, code, message, error }` 4필드 구조가 아니라 `message` 하나뿐이다. 클라이언트가 `code`로 분기할 방법이 없고, 각 예외 타입과 HTTP 상태 코드의 매핑도 `AccountNotFoundException`(404)과 그 외 전부(400) 두 그룹으로 뭉뚱그려져 있다 — `InsufficientBalanceException`과 `AccountAlreadyClosedException`이 똑같이 400으로만 구분된다. **아래는 root 원칙에 맞는 올바른 구조이며, `examples/`에는 아직 반영되어 있지 않다.**

---

## 올바른 응답 형식 — `ErrorResponse` 확장

```kotlin
// interfaces/rest/ErrorResponse.kt — 제안
data class ErrorResponse(
    val statusCode: Int,
    val code: String,
    val message: String,
    val error: String,
)
```

## 에러 코드 — enum으로 예외 타입과 1:1 매핑

```kotlin
// domain/AccountErrorCode.kt — 제안
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
}
```

`AccountException`의 각 하위 클래스에 코드와 HTTP 상태를 프로퍼티로 갖게 하면, `sealed class`의 exhaustiveness 검사를 에러 변환 시점에도 활용할 수 있다.

```kotlin
// domain/AccountException.kt — 제안
sealed class AccountException(
    message: String,
    val code: AccountErrorCode,
    val httpStatus: HttpStatus,
) : RuntimeException(message)

class AccountNotFoundException(accountId: String) :
    AccountException("account not found: $accountId", AccountErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)

class InsufficientBalanceException :
    AccountException("잔액이 부족합니다.", AccountErrorCode.INSUFFICIENT_BALANCE, HttpStatus.BAD_REQUEST)

class AccountAlreadyClosedException :
    AccountException("이미 종료된 계좌입니다.", AccountErrorCode.ACCOUNT_ALREADY_CLOSED, HttpStatus.CONFLICT)
// ...
```

`httpStatus`를 domain 예외에 두는 것이 root의 "Domain/Application은 HTTP 무의존" 원칙과 다소 긴장 관계에 있다는 반론도 가능하지만, `HttpStatus`는 Spring Web의 단순 enum(의존성 없는 상수 나열)이라 domain이 Spring MVC 프레임워크 자체(서블릿, `@Controller` 등)에 결합되는 것은 아니다. 더 엄격하게 분리하려면 `httpStatus: HttpStatus` 대신 domain에는 `code`만 두고, Interface 레이어의 매핑 테이블(`code → HttpStatus`)에서 변환해도 된다 — 아래는 그 방식을 보여준다.

---

## Interface 레이어 — `@RestControllerAdvice`로 전역 처리

각 Controller에 `@ExceptionHandler`를 흩어두는 대신, `@RestControllerAdvice`로 애플리케이션 전역에서 한 번만 처리한다.

```kotlin
// common/GlobalExceptionHandler.kt — 제안
package com.example.accountservice.common

import com.example.accountservice.account.domain.AccountException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.httpStatus).body(
            ErrorResponse(
                statusCode = e.httpStatus.value(),
                code = e.code.name,
                message = e.message ?: "",
                error = e.httpStatus.reasonPhrase,
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(
                statusCode = 400,
                code = "VALIDATION_FAILED",
                message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" },
                error = "Bad Request",
            ),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.internalServerError().body(
            ErrorResponse(500, "INTERNAL_SERVER_ERROR", "예상치 못한 오류가 발생했습니다.", "Internal Server Error"),
        )
}
```

`@RestControllerAdvice`로 옮기면 `AccountController`에서 `@ExceptionHandler` 메서드 두 개가 사라지고, 새 Controller(향후 다른 도메인)를 추가해도 동일한 전역 핸들러를 재사용한다 — Kotlin이 특별히 다른 점은 없지만, 예외 매칭이 `sealed class` 계층 덕분에 `AccountException`(부모) 하나만 잡아도 모든 하위 타입이 자동으로 커버된다는 것이 이점이다(신규 하위 예외 추가 시 핸들러 코드 수정 불필요).

**분류되지 않은 예외(Exception::class)를 마지막에 잡아 500으로 응답하는 것이 필수**다 — 그렇지 않으면 예상치 못한 예외가 Spring의 기본 에러 페이지(HTML)로 노출되어 API 클라이언트가 JSON을 기대하는 계약을 깬다.

---

## Validation 실패 응답 예시

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": "email: 올바른 이메일 형식이 아닙니다",
  "error": "Bad Request"
}
```

---

## 원칙 요약

- **`sealed class` 예외 계층은 유지**한다 — 이미 root 원칙과 일치하는 Kotlin다운 구현이다.
- **`AccountErrorCode` enum을 추가**해 각 예외와 1:1 매핑한다.
- **`ErrorResponse`를 4필드(`statusCode`/`code`/`message`/`error`)로 확장**한다.
- **`@RestControllerAdvice`로 에러 변환을 전역화**한다 — Controller마다 `@ExceptionHandler`를 반복하지 않는다.
- **분류되지 않은 예외는 500으로 캐치-올**한다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부에서 예외 throw
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 에러 변환이 일어나는 파이프라인 위치
