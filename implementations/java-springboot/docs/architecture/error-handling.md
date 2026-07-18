# 에러 처리 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [error-handling.md](../../../../docs/architecture/error-handling.md) 참고.

## 계층별 에러 처리 — 현재 구현은 원칙과 일치

| 레이어 | 처리 방식 | 이 저장소의 구현 |
|---|---|---|
| Domain | 타입화된 예외 throw | `AccountException(ErrorCode, message)` |
| Application | 그대로 전파 (catch하지 않음) | Command/Query Service는 예외를 잡지 않는다 |
| Interface | 예외 → HTTP 응답 변환 | `AccountController.handleAccountException` (`@ExceptionHandler`) |

```java
// account/domain/AccountException.java — 실제 코드
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

- Domain 메서드(`Account.deposit()` 등)가 불변식 위반 시 `AccountException`을 즉시 throw한다 — root의 "Domain은 plain Error/타입화 예외만 throw" 원칙과 일치한다(`RuntimeException` 상속이 Java의 "plain Error"에 해당).
- **`ErrorCode`가 enum이라 오타가 컴파일 타임에 잡힌다** — root가 TypeScript enum 키=값 트릭으로 얻으려는 효과(`OrderErrorMessage['...']`를 통해서만 메시지를 쓰게 강제)를, Java는 enum 상수 참조(`ErrorCode.INSUFFICIENT_BALANCE`)로 자연스럽게 얻는다. free-form 문자열로 코드를 표현할 수 없다.
- 메시지(`"잔액이 부족합니다."`)는 생성자 인자로 매번 직접 넘긴다 — root의 `<Domain>ErrorMessage` enum처럼 메시지 자체를 타입화하지는 않았다. `ErrorCode` 하나당 메시지가 항상 고정이라면(현재 코드가 실제로 그렇다), 메시지를 `ErrorCode`에 내장하는 편이 root 원칙(에러 메시지도 enum으로 타입화)에 더 가깝다 — 아래 "개선안" 참고.

```java
// interfaces/rest/AccountController.java — 실제 코드
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
}
```

`@ExceptionHandler`가 `AccountException`을 catch하여 HTTP 상태 코드로 변환하는 지점이 Interface 레이어(Controller)에만 있다는 것도 root 원칙과 일치한다.

---

## 에러 응답 형식 — 4필드, 루트 형식과 일치

루트 원칙: 모든 에러 응답은 `statusCode`/`code`/`message`/`error` 4필드를 갖는다.

```java
// interfaces/rest/ErrorResponse.java — 실제 코드
public record ErrorResponse(int statusCode, String code, String message, String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
```

`statusCode`(HTTP 상태 코드 숫자)와 `error`(HTTP 상태 텍스트, 예: `"Not Found"`)까지 응답 바디에 모두 포함되어, 클라이언트가 응답 바디만 보고도 HTTP 상태를 재확인할 수 있다.

```java
// AccountController — 실제 코드
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
}
```

응답 예시:

```json
{
  "statusCode": 404,
  "code": "ACCOUNT_NOT_FOUND",
  "message": "계좌를 찾을 수 없습니다.",
  "error": "Not Found"
}
```

**남은 여지**: `AccountControllerE2ETest`는 현재 `response.getBody().get("code")`만 검증하고 `statusCode`/`error` 필드 값은 별도로 단언하지 않는다 — 응답 바디 자체에는 이미 4필드가 모두 채워지므로 회귀는 없지만, root 형식 준수 여부를 테스트로도 명시적으로 고정하려면 `assertThat(body.get("statusCode")).isEqualTo(404)` 같은 단언을 추가할 수 있다.

---

## Validation 실패 응답 — 전역 핸들러가 처리

`@Valid @RequestBody CreateAccountRequest request`가 Bean Validation에 실패하면 Spring이 `MethodArgumentNotValidException`을 던진다. `common/web/GlobalExceptionHandler`(`@RestControllerAdvice`)가 이를 처리해, `AccountException`과 같은 4필드 `ErrorResponse` 스키마로 응답한다.

```java
// common/web/GlobalExceptionHandler.java — 실제 코드
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

root 원칙: Validation 실패의 `code`는 항상 `VALIDATION_FAILED` 고정값이다. 여러 필드 에러가 있을 수 있으므로 `message`는 문자열 배열이 될 수도 있다(root 예시 참고) — 이 저장소는 join된 단일 문자열로 단순화했다.

**`@ExceptionHandler`를 `AccountController`가 아닌 `@RestControllerAdvice` 전역 클래스에 두는 이유**: 도메인이 늘어나면 각 Controller가 `MethodArgumentNotValidException` 핸들러를 중복 정의하게 된다. Validation처럼 도메인에 무관한 공통 예외는 전역에서 한 번만 처리하고, `AccountException`처럼 도메인 특화 예외만 각 Controller(`AccountController`/`CardController`/`AuthController`)에 남긴다 — rate limit(`RequestNotPermitted`) 핸들러와 같은 배치 원칙이다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부 예외 throw 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 에러 처리 책임 분리
- [testing.md](testing.md) — E2E 테스트에서 에러 응답 형식 검증
