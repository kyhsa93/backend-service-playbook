# API Response Structure (Go)

The principle follows the root [api-response.md](../../../../docs/architecture/api-response.md): offset pagination (`page`/`take`, 0-based), list responses use a domain-plural key + `count`, and single-item responses return the domain object directly with no generic wrapper.

This repository's Go example (`internal/interface/http/`) follows this rule — this document explains the rule grounded in the actual code.

---

## Pagination — parsing query parameters

Go has no framework-level automatic DTO binding, so parsing is done directly from `net/http`'s `r.URL.Query()`. `internal/interface/http/account_handler.go`:

```go
func parsePagination(r *http.Request) (page, take int) {
	page, take = 0, 20
	if v, err := strconv.Atoi(r.URL.Query().Get("page")); err == nil {
		page = v
	}
	if v, err := strconv.Atoi(r.URL.Query().Get("take")); err == nil {
		take = v
	}
	return page, take
}
```

- If parsing fails (`strconv.Atoi` error), the default value is kept — this is lenient parsing that absorbs bad query parameters into a default rather than throwing a 400. If strict validation is needed, this can be changed to convert the error into a 400.
- `page=0` is the default, and the `skip = page * take` calculation follows naturally.

```go
// used in internal/application/query/get_transactions_handler.go
transactions, count, err := h.repo.FindTransactions(ctx, q.AccountID, q.Page, q.Take)
```

```go
// internal/infrastructure/persistence/account_repository.go — converted into SQL
args = append(args, q.Take, q.Page*q.Take)
rows, err := r.db.QueryContext(ctx,
	fmt.Sprintf(`... ORDER BY id DESC LIMIT $%d OFFSET $%d`, i, i+1), args...)
```

---

## List response — domain-plural key + count

`GetTransactionsResponse` uses the plural of the domain noun (`transactions`) as the key, instead of a generic key like `result`/`data`/`items`.

```go
// internal/interface/http/dto.go
type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"`
	Count        int                          `json:"count"`
}
```

`Count` is not the page size but **the total count after filters are applied**. `FindTransactions` in `internal/infrastructure/persistence/account_repository.go` runs a separate `SELECT COUNT(*)` to get `total`, and `FindAccounts` follows the same pattern (two queries: a `COUNT(*)` query and a `LIMIT/OFFSET` query).

The prohibition on generic keys like `result`/`data`/`items` is automatically checked by `implementations/go/harness/no_generic_response_keys.go` (the `no-generic-response-keys` rule) — it flags FAIL if any struct declaration under `internal/interface/**` has a field tagged `json:"result"`/`json:"data"`/`json:"items"`.

---

## Single-item response — no generic wrapper

`GetAccountResponse` exposes its fields flat, with no wrapper like `{ success: true, data: {...} }`.

```go
// internal/interface/http/dto.go
type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	OwnerID   string        `json:"ownerId"`
	Email     string        `json:"email"`
	Balance   MoneyResponse `json:"balance"`
	Status    string        `json:"status"`
	CreatedAt time.Time     `json:"createdAt"`
	UpdatedAt time.Time     `json:"updatedAt"`
}
```

The distinction between error and success responses is handled by the HTTP status code (see [error-handling.md](error-handling.md) for the detailed mapping). No `Success bool` field is added to Go response DTOs.

---

## Repository query methods — return an array + count

The `FindAccounts` method on the `account.Repository` interface follows the root document's "list queries always return an array of domain objects + count" as-is.

```go
// internal/domain/account/repository.go
type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	SaveAccount(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

Go's multiple return values (`([]*Account, int, error)`) play the same role as TypeScript's `Promise<{ orders: Order[]; count: number }>` — expressed via a language feature instead of a separate Result wrapper type.

### Single-item lookup — `find<Noun>s` + `take: 1`

As the root document recommends, no dedicated method equivalent to `findOne` is added separately — a single-item lookup is mimicked by calling `FindAccounts` with `FindQuery{AccountID, OwnerID, Take: 1}`. This repeated pattern (pull out the first result, `ErrNotFound` if none) is extracted into the `FindOne(ctx, q, accountID, ownerID)` helper in `internal/domain/account/repository.go`, reused by every call site — it plays the same role as java/kotlin-springboot's `findAccounts(...).stream().findFirst().orElseThrow(...)`. See [repository-pattern.md](repository-pattern.md) for details.

---

## Dynamic filter condition pattern

`FindAccounts` in `internal/infrastructure/persistence/account_repository.go` adds a WHERE condition only when a value is present.

```go
where := []string{"deleted_at IS NULL"}
if q.AccountID != "" {
	where = append(where, fmt.Sprintf("id = $%d", i))
	args = append(args, q.AccountID)
	i++
}
if q.OwnerID != "" {
	where = append(where, fmt.Sprintf("owner_id = $%d", i))
	args = append(args, q.OwnerID)
	i++
}
if len(q.Status) > 0 {
	// IN condition — an empty slice is excluded from the condition
}
```

In Go, the zero value (`""`, `nil`, `len() == 0`) is the natural expression of "no value." The same intent as TypeScript's `if (query.field)` guard is implemented via a Go zero-value check.

---

## Result objects — the domain Aggregate is never exposed directly

The Query Handlers in `internal/application/query/` never return `account.Account` (the Aggregate) as-is — they convert it into a response-only Result struct (`GetAccountResult`, `TransactionSummary`).

```go
// internal/application/query/result.go
type GetAccountResult struct {
	AccountID string
	OwnerID   string
	Email     string
	Balance   MoneyResult
	Status    string
	CreatedAt time.Time
	UpdatedAt time.Time
}
```

```go
// internal/application/query/get_account_handler.go
func (h *GetAccountHandler) Handle(ctx context.Context, q GetAccountQuery) (*GetAccountResult, error) {
	a, err := account.FindOne(ctx, h.repo, q.AccountID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return &GetAccountResult{
		AccountID: a.AccountID,
		// ... Aggregate fields are mapped explicitly to Result fields
	}, nil
}
```

`interface/http/account_handler.go` then maps `GetAccountResult` again into `GetAccountResponse` (the JSON DTO) — it passes through Domain → Application Result → Interface DTO in three stages, and even though each stage is a thin field mapping, the layer boundaries stay clearly maintained.

Whether a Query Handler returns the raw Aggregate directly is automatically checked by `implementations/go/harness/query_handler_no_raw_aggregate.go` (the `query-handler-no-raw-aggregate` rule) — it flags FAIL if `Handle()` in `internal/application/query/*.go` returns a pointer type qualified by an `internal/domain/<bc>` package (e.g. `*account.Account`).

---

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository method design
- [layer-architecture.md](layer-architecture.md) — Result objects and conversion across layers
- [error-handling.md](error-handling.md) — mapping errors to HTTP status codes
