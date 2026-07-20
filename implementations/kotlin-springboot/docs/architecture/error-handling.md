# 에러 처리 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root error-handling.md](../../../../docs/architecture/error-handling.md) 참조.

## 현재 구현 — sealed class 예외 계층

```kotlin
// domain/AccountException.kt — 실제 코드
sealed class AccountException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : AccountException("account not found: $accountId")
class InvalidAmountException : AccountException("금액은 0보다 커야 합니다.")
class InsufficientBalanceException : AccountException("잔액이 부족합니다.")
// ...
```

`sealed class`는 root의 "plain Error + 타입화된 에러 메시지" 원칙을 Kotlin다운 방식으로 표현한다 — 같은 모듈(파일) 내에서만 상속 가능하므로 컴파일러가 하위 타입 전체를 알고, `when (exception) { ... }`으로 분기하면 새 예외 추가 시 처리 누락을 컴파일 타임에 잡아낼 수 있다. harness의 `sealed-exception` 검사가 이 계층이 `domain/`에 있는지 확인한다.

각 하위 클래스는 이제 root가 요구하는 에러 코드(`AccountErrorCode`)와 1:1 매핑되는 `code` 프로퍼티를 갖는다 — 아래에서 실제 구조를 설명한다.

---

## 응답 형식 — `ErrorResponse` 4필드

```kotlin
// interfaces/rest/Schemas.kt — 실제 코드
data class ErrorResponse(
    val statusCode: Int,
    val code: String,
    val message: String,
    val error: String,
)
```

root가 요구하는 `{ statusCode, code, message, error }` 4필드 구조를 그대로 구현한다. 클라이언트는 `code`로 분기할 수 있고, `AccountNotFoundException`(404)과 그 외 `AccountException` 전부(400)는 여전히 두 그룹으로 나뉘지만 — `code` 필드가 `InsufficientBalanceException`(`INSUFFICIENT_BALANCE`)과 `AccountAlreadyClosedException`(`ACCOUNT_ALREADY_CLOSED`)처럼 동일한 400 안에서도 정확한 원인을 구분해준다. HTTP 상태 코드로 원인을 세분화하는 대신(예: 409 CONFLICT) `code`로 구분하는 이유는 기존 E2E 테스트(`AccountControllerE2ETest`)가 이미 이 예외들을 전부 400으로 검증하고 있어, 상태 코드 세분화가 하위 호환을 깨기 때문이다 — HTTP 상태는 "범주", `code`가 "정확한 원인"이라는 root 원칙과 일치한다.

## 에러 코드 — enum으로 예외 타입과 1:1 매핑

```kotlin
// domain/AccountErrorCode.kt — 실제 코드
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

`AccountException`의 각 하위 클래스가 코드를 프로퍼티로 갖는다 — `sealed class`라 새 하위 타입을 추가하면서 `code`를 빠뜨리면 컴파일 타임에 드러난다.

```kotlin
// domain/AccountException.kt — 실제 코드
sealed class AccountException(message: String, val code: AccountErrorCode) : RuntimeException(message)

class AccountNotFoundException(accountId: String) :
    AccountException("account not found: $accountId", AccountErrorCode.ACCOUNT_NOT_FOUND)

class InsufficientBalanceException :
    AccountException("잔액이 부족합니다.", AccountErrorCode.INSUFFICIENT_BALANCE)

class AccountAlreadyClosedException :
    AccountException("이미 종료된 계좌입니다.", AccountErrorCode.ACCOUNT_ALREADY_CLOSED)
// ...
```

`httpStatus`는 domain 예외가 아니라 Interface 레이어(`GlobalExceptionHandler`)의 매핑에만 존재한다 — domain/application 레이어는 `code`만 알고 HTTP에는 전혀 의존하지 않는다. 이는 error-handling.md가 제시하던 두 대안(①`httpStatus`를 domain 예외에 두는 방식, ②Interface 레이어 매핑 테이블로 분리하는 방식) 중 **더 엄격한 ②를 채택**한 것이다.

---

## Interface 레이어 — `@RestControllerAdvice`로 전역 처리

각 Controller에 `@ExceptionHandler`를 흩어두는 대신, `@RestControllerAdvice`로 애플리케이션 전역에서 한 번만 처리한다. `AccountController`에는 `@ExceptionHandler` 메서드가 없다.

```kotlin
// common/GlobalExceptionHandler.kt — 실제 코드
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
        logger.warn("계좌를 찾을 수 없음: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> {
        logger.warn("계좌 요청 실패: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("예상치 못한 오류", e)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "예상치 못한 오류가 발생했습니다.")
    }

    private fun errorResponse(status: HttpStatus, code: String, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(status.value(), code, message, status.reasonPhrase))
}
```

`AccountNotFoundException` 전용 핸들러가 `AccountException`(부모) 핸들러보다 먼저 선언되어 있지만, Spring의 `@ExceptionHandler` 매칭은 선언 순서가 아니라 예외 계층에서 가장 구체적인 타입을 우선 선택하므로 순서는 상관없다. `AccountController`에서 `@ExceptionHandler` 메서드 두 개가 사라졌고, 새 Controller(향후 다른 도메인)를 추가해도 동일한 전역 핸들러를 재사용한다 — 예외 매칭이 `sealed class` 계층 덕분에 `AccountException`(부모) 하나만 잡아도 모든 하위 타입이 자동으로 커버된다는 것이 이점이다(신규 하위 예외 추가 시 핸들러 코드 수정 불필요).

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
- **`AccountErrorCode` enum 추가 완료** — 각 예외와 1:1 매핑됨.
- **`ErrorResponse`를 4필드(`statusCode`/`code`/`message`/`error`)로 확장 완료**.
- **`@RestControllerAdvice`로 에러 변환을 전역화한다** — `AccountController`에는 `@ExceptionHandler`가 없고, `common/GlobalExceptionHandler.kt` 하나로 통합되어 있다.
- **분류되지 않은 예외는 500으로 캐치-올**한다 — `handleUnknown(Exception::class)`으로 구현됨.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부에서 예외 throw
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 에러 변환이 일어나는 파이프라인 위치
- harness `error-response-schema`/`typed-errors-only` 규칙(`../../harness/README.md`) — `ErrorResponse`가 정확히 4필드인지, domain/application이 `RuntimeException("...")` 같은 free-form 문자열 예외 대신 sealed class 타입화 예외만 쓰는지 기계 검증
