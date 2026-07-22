# Error Handling (Go)

The principle follows the root [error-handling.md](../../../../docs/architecture/error-handling.md): the Domain/Application layers return only plain errors with no framework exceptions, and HTTP status code conversion happens only in the Interface layer. Go has no exceptions at all — `error` is explicitly declared as a return value — so this layer separation is actually easier to enforce in code than in TypeScript: if a function signature has no `error`, that means the function simply cannot fail.

---

## Sentinel error — `var ErrXxx = errors.New(...)`

The Go idiom corresponding to the root's requirement for a "typed error message" (TypeScript's enum) is the **sentinel error variable**. This repository already uses this pattern in `internal/domain/account/errors.go`.

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

This plays the combined role of the root's `<Domain>ErrorMessage` enum and `<Domain>ErrorCode` enum — Go simply has no equivalent to the problem of "a key/value enum drifting out of sync by mistake." `errors.Is(err, account.ErrNotFound)` compares the variable's **identity** (pointer equality), so a mapping can never break due to a typo in a string. The client-facing `code` the root document requires (a SCREAMING_SNAKE_CASE string like `ACCOUNT_NOT_FOUND`) is defined side by side with the sentinel error in the Interface layer's `accountErrorMapping` table — see "Standard error response JSON schema" below.

---

## Returning errors from domain methods

An Aggregate method returns a sentinel error immediately when an invariant is violated (`internal/domain/account/account.go`):

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

Since Go has no exceptions, "throw immediately" is expressed as "immediately `return zero value, err`." A caller ignoring the returned `error` is often not caught by `go vet`/linters, so every call site should always check `if err != nil` rather than skip it.

---

## Wrapping with `fmt.Errorf` — adding call-stack context

As an error crosses the Infrastructure/Application layers, context about which operation failed is appended. Wrapping with `%w` wraps the original error while still allowing `errors.Is`/`errors.Unwrap` to keep finding the original.

```go
// internal/domain/account/repository.go — the point where the FindOne helper returns ErrNotFound
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

**Rules:**
- If it's already a sentinel error (`account.ErrNotFound`), pass it through as-is — rewrapping it unnecessarily only lengthens the `errors.Is` chain without adding information.
- An error of unknown cause, such as a DB driver error, is wrapped with `fmt.Errorf("<operation description>: %w", err)` so logs can trace which layer's which operation failed.
- Messages start lowercase and carry no punctuation (standard Go convention — so the message reads naturally when concatenated after another error message).

---

## Interface layer — mapping to HTTP status codes with `errors.Is`

The responsibility of converting an error into an HTTP status code lives only in the Interface layer (`internal/interface/http/account_handler.go`). Not just the status code but also the client-facing `code` the root document requires (a SCREAMING_SNAKE_CASE string like `ACCOUNT_NOT_FOUND`) is assigned together in this same table:

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

An error not in the mapping (i.e. one that doesn't match anything in the for loop) is treated as a 500, and the original message is never exposed to the client — this prevents internal implementation details (DB column names, driver error strings, etc.) from leaking. Because `errors.Is` is used, the mapping doesn't break even if a Repository wraps and returns the error as `fmt.Errorf("find account by id: %w", account.ErrNotFound)`.

---

## Standard error response JSON schema — `writeJSONError`

The root document requires every error response to follow the format below, and this repository implements this schema as-is.

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

When `writeAccountError` finds a sentinel error, it passes the `accountErrorMapping` table's `status`/`code` straight to `writeJSONError`. The `error` field is filled with `http.StatusText(status)`, returning the HTTP status text ("Not Found", "Bad Request", etc.) as-is — this carries the same meaning as the root schema's `error` field.

---

## The response schema is automatically checked by the harness

Whether an error response has exactly the 4 fields `statusCode`/`code`/`message`/`error` (any deviation — more fields, fewer fields, or different names — is a violation) is automatically checked by `implementations/go/harness/error_response_schema.go` (the `error-response-schema` rule) — it finds struct candidates for error responses under `internal/interface/http/**` by their `json:"statusCode"` tag and compares the set of JSON fields.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the pattern of returning errors from inside Aggregate methods
- [layer-architecture.md](layer-architecture.md) — separation of error-handling responsibility by layer
- [api-response.md](api-response.md) — contrast with the success response format
