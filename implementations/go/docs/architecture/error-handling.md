# 에러 처리 (Go)

원칙은 루트 [error-handling.md](../../../../docs/architecture/error-handling.md)를 따른다: Domain/Application 레이어는 프레임워크 예외 없이 plain error만 반환하고, HTTP 상태 코드 변환은 Interface 레이어에서만 수행한다. Go는 예외(exception) 자체가 없고 `error`를 반환값으로 명시하므로, 이 레이어 분리가 TypeScript보다 오히려 코드로 강제하기 쉽다 — 함수 시그니처에 `error`가 없으면 애초에 실패할 수 없는 함수라는 뜻이다.

---

## Sentinel Error — `var ErrXxx = errors.New(...)`

root가 요구하는 "타입화된 에러 메시지"(TypeScript의 enum)에 대응하는 Go 관용구는 **sentinel error 변수**다. 이 저장소는 `internal/domain/account/errors.go`에서 이미 이 패턴을 쓰고 있다.

```go
// internal/domain/account/errors.go
package account

import "errors"

var (
	ErrNotFound                           = errors.New("account not found")
	ErrInvalidAmount                      = errors.New("amount must be greater than zero")
	ErrInvalidMoneyAmount                 = errors.New("money amount must be zero or greater")
	ErrCurrencyMismatch                   = errors.New("currency mismatch")
	ErrDepositRequiresActiveAccount       = errors.New("account must be active to deposit")
	ErrWithdrawRequiresActiveAccount      = errors.New("account must be active to withdraw")
	ErrInsufficientBalance                = errors.New("insufficient balance")
	ErrSuspendRequiresActiveAccount       = errors.New("account must be active to suspend")
	ErrReactivateRequiresSuspendedAccount = errors.New("account must be suspended to reactivate")
	ErrAlreadyClosed                      = errors.New("account already closed")
	ErrBalanceNotZero                     = errors.New("balance must be zero to close account")
)
```

이것이 root의 `<Domain>ErrorMessage` enum과 `<Domain>ErrorCode` enum을 합친 역할을 겸한다 — Go에는 "키/값 enum이 실수로 어긋나는" 문제 자체가 없다. `errors.Is(err, account.ErrNotFound)`는 변수의 **아이덴티티**(포인터 동일성)를 비교하므로 문자열 오타로 매핑이 깨질 수 없다. root가 요구하는 client-facing `code`(`ACCOUNT_NOT_FOUND` 같은 SCREAMING_SNAKE_CASE 문자열)는 Interface 레이어의 `accountErrorMapping` 테이블이 sentinel error 옆에 나란히 정의한다 — 아래 "표준 에러 응답 JSON 스키마" 참고.

---

## 도메인 메서드에서 에러 반환

Aggregate 메서드는 불변식 위반 시 즉시 sentinel error를 반환한다(`internal/domain/account/account.go`):

```go
func (a *Account) Withdraw(amount int64) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	if amount <= 0 {
		return Transaction{}, ErrInvalidAmount
	}
	// ...
	if a.Balance.LessThan(money) {
		return Transaction{}, ErrInsufficientBalance
	}
	// ...
}
```

Go에는 예외가 없으므로 "즉시 throw"는 "즉시 `return zero값, err`"로 표현된다. 호출자가 반환된 `error`를 무시하면 `go vet`/린터가 잡아내지 못하는 경우가 많으므로, 모든 호출부에서 `if err != nil` 체크를 생략하지 않는다.

---

## `fmt.Errorf`로 래핑 — 호출 스택 컨텍스트 추가

Infrastructure/Application 레이어를 거치며 에러에 어떤 작업이 실패했는지 컨텍스트를 덧붙인다. `%w`로 래핑하면 원본 에러를 감싸면서도 `errors.Is`/`errors.Unwrap`으로 원본을 계속 찾을 수 있다.

```go
// internal/domain/account/repository.go — FindOne 헬퍼가 ErrNotFound를 반환하는 지점
func FindOne(ctx context.Context, q Query, accountID, ownerID string) (*Account, error) {
	accounts, _, err := q.FindAccounts(ctx, FindQuery{AccountID: accountID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(accounts) == 0 {
		return nil, ErrNotFound
	}
	return accounts[0], nil
}
```

```go
// internal/application/command/deposit_handler.go
a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
if err != nil {
	return nil, fmt.Errorf("deposit: %w", err)
}
```

**규칙:**
- 이미 sentinel error인 경우(`account.ErrNotFound`)는 그대로 전달한다 — 불필요하게 다시 래핑하면 `errors.Is` 체인이 길어질 뿐 정보가 늘지 않는다.
- DB 드라이버 에러 같은 "원인 불명 에러"는 `fmt.Errorf("<작업 설명>: %w", err)`로 래핑해 로그에서 어느 계층의 어떤 작업이 실패했는지 추적 가능하게 한다.
- 메시지는 소문자로 시작하고 구두점을 찍지 않는다(Go 표준 컨벤션 — 다른 에러 메시지에 이어붙여도 문장이 어색하지 않도록).

---

## Interface 레이어 — `errors.Is`로 HTTP 상태 코드 매핑

에러를 HTTP 상태 코드로 변환하는 책임은 Interface 레이어에만 있다(`internal/interface/http/account_handler.go`). 상태 코드뿐 아니라 root가 요구하는 client-facing `code`(`ACCOUNT_NOT_FOUND` 같은 SCREAMING_SNAKE_CASE 문자열)도 이 테이블에서 함께 부여한다:

```go
// internal/interface/http/account_handler.go
var accountErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{account.ErrNotFound, http.StatusNotFound, "ACCOUNT_NOT_FOUND"},
	{account.ErrInvalidAmount, http.StatusBadRequest, "ACCOUNT_INVALID_AMOUNT"},
	{account.ErrDepositRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrWithdrawRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrInsufficientBalance, http.StatusBadRequest, "ACCOUNT_INSUFFICIENT_BALANCE"},
	{account.ErrSuspendRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_SUSPEND_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrReactivateRequiresSuspendedAccount, http.StatusBadRequest, "ACCOUNT_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT"},
	{account.ErrAlreadyClosed, http.StatusBadRequest, "ACCOUNT_ALREADY_CLOSED"},
	{account.ErrBalanceNotZero, http.StatusBadRequest, "ACCOUNT_BALANCE_NOT_ZERO"},
}

func writeAccountError(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range accountErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled account error", "error", err)
	writeJSONError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}
```

매핑에 없는 에러(즉 for 루프를 통과하지 못하는 경우)는 500으로 처리되고, 원본 메시지는 클라이언트에 노출하지 않는다 — 내부 구현 세부사항(DB 컬럼명, 드라이버 에러 문자열 등)이 새어나가는 것을 막는다. `errors.Is`를 쓰기 때문에 Repository가 `fmt.Errorf("find account by id: %w", account.ErrNotFound)`처럼 래핑해서 반환해도 매핑이 깨지지 않는다.

---

## 표준 에러 응답 JSON 스키마 — `writeJSONError`

root는 모든 에러 응답이 아래 형식을 따르도록 요구하며, 이 저장소는 이 스키마를 그대로 구현한다.

```json
{ "statusCode": 404, "code": "ACCOUNT_NOT_FOUND", "message": "account not found", "error": "Not Found" }
```

```go
// internal/interface/http/dto.go
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// internal/interface/http/account_handler.go
func writeJSONError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(ErrorResponse{
		StatusCode: status,
		Code:       code,
		Message:    message,
		Error:      http.StatusText(status),
	})
}
```

`writeAccountError`는 sentinel error를 찾으면 `accountErrorMapping` 테이블의 `status`/`code`를 그대로 `writeJSONError`에 넘긴다. `error` 필드는 `http.StatusText(status)`로 채워 HTTP 상태 텍스트("Not Found", "Bad Request" 등)를 그대로 반환한다 — root 스키마의 `error` 필드와 동일한 의미다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 메서드 내부에서 에러 반환하는 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 에러 처리 책임 분리
- [api-response.md](api-response.md) — 정상 응답 형식과의 대비
