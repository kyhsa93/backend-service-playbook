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

이것이 root의 `<Domain>ErrorMessage` enum과 `<Domain>ErrorCode` enum을 합친 역할을 겸한다 — Go에는 "키/값 enum이 실수로 어긋나는" 문제 자체가 없다. `errors.Is(err, account.ErrNotFound)`는 변수의 **아이덴티티**(포인터 동일성)를 비교하므로 문자열 오타로 매핑이 깨질 수 없다. 반대로 root가 요구하는 client-facing `code`(`ORDER_NOT_FOUND` 같은 SCREAMING_SNAKE_CASE 문자열)는 Go 쪽엔 아직 없다 — 아래 "알려진 격차" 참고.

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
// internal/infrastructure/persistence/account_repository.go
if err := row.Scan(&id, &ownerIDCol, &email, &amount, &currency, &status, &createdAt, &updatedAt); err != nil {
	if err == sql.ErrNoRows {
		return nil, account.ErrNotFound
	}
	return nil, fmt.Errorf("find account by id: %w", err)
}
```

```go
// internal/application/command/deposit_handler.go
a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
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

에러를 HTTP 상태 코드로 변환하는 책임은 Interface 레이어에만 있다(`internal/interface/http/account_handler.go`):

```go
func writeAccountError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, account.ErrInvalidAmount),
		errors.Is(err, account.ErrDepositRequiresActiveAccount),
		errors.Is(err, account.ErrWithdrawRequiresActiveAccount),
		errors.Is(err, account.ErrInsufficientBalance),
		errors.Is(err, account.ErrSuspendRequiresActiveAccount),
		errors.Is(err, account.ErrReactivateRequiresSuspendedAccount),
		errors.Is(err, account.ErrAlreadyClosed),
		errors.Is(err, account.ErrBalanceNotZero):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

매핑에 없는 에러(즉 `default` 분기)는 500으로 처리되고, 원본 메시지는 클라이언트에 노출하지 않는다 — 내부 구현 세부사항(DB 컬럼명, 드라이버 에러 문자열 등)이 새어나가는 것을 막는다. `errors.Is`를 쓰기 때문에 Repository가 `fmt.Errorf("find account by id: %w", account.ErrNotFound)`처럼 래핑해서 반환해도 매핑이 깨지지 않는다.

---

## 알려진 격차 — 표준 에러 응답 JSON 스키마 미구현

root는 모든 에러 응답이 아래 형식을 따르도록 요구한다.

```json
{ "statusCode": 404, "code": "ACCOUNT_NOT_FOUND", "message": "account not found", "error": "Not Found" }
```

현재 `writeAccountError`는 `http.Error(w, err.Error(), status)`로 **평문 텍스트**만 반환한다(`Content-Type: text/plain`, JSON 구조 없음). `code`(클라이언트 분기용 안정적 문자열)에 해당하는 필드도 없다 — HTTP 상태 코드만으로 원인을 구분해야 한다. 새로 이 영역을 손볼 때는:

```go
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

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

이렇게 sentinel error마다 `code` 문자열을 매핑하는 테이블을 두는 방향으로 확장한다. 이번 문서화 패스에서는 `examples/` 코드 자체는 바꾸지 않는다 — 위 스니펫은 앞으로 이 영역을 구현할 때의 목표 형태다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 메서드 내부에서 에러 반환하는 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 에러 처리 책임 분리
- [api-response.md](api-response.md) — 정상 응답 형식과의 대비
