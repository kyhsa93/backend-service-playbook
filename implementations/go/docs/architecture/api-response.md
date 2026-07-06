# API 응답 구조 (Go)

원칙은 루트 [api-response.md](../../../../docs/architecture/api-response.md)를 따른다: 오프셋 페이지네이션(`page`/`take`, 0-base), 목록 응답은 도메인 복수형 키 + `count`, 단건 응답은 범용 래퍼 없이 도메인 객체를 직접 반환한다.

이 저장소의 Go 예제(`internal/interface/http/`)는 이 규칙을 이미 따르고 있다 — 이 문서는 실제 코드를 근거로 규칙을 설명한다.

---

## 페이지네이션 — 쿼리 파라미터 파싱

Go에는 프레임워크의 자동 DTO 바인딩이 없으므로 `net/http`의 `r.URL.Query()`에서 직접 파싱한다. `internal/interface/http/account_handler.go`:

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

- 파싱 실패(`strconv.Atoi` 에러) 시 기본값을 유지한다 — 잘못된 쿼리 파라미터로 400을 던지기보다 기본값으로 흡수하는 관대한 파싱이다. 엄격한 검증이 필요하면 에러를 400으로 변환하도록 바꿀 수 있다.
- `page=0`이 기본값이며 `skip = page * take` 계산이 자연스럽다.

```go
// internal/application/query/get_transactions_handler.go에서 사용
transactions, count, err := h.repo.FindTransactions(ctx, q.AccountID, q.Page, q.Take)
```

```go
// internal/infrastructure/persistence/account_repository.go — SQL로 변환
args = append(args, q.Take, q.Page*q.Take)
rows, err := r.db.QueryContext(ctx,
	fmt.Sprintf(`... ORDER BY id DESC LIMIT $%d OFFSET $%d`, i, i+1), args...)
```

---

## 목록 조회 응답 — 도메인 복수형 키 + count

`GetTransactionsResponse`는 `result`/`data`/`items` 같은 범용 키 대신 도메인 명사의 복수형(`transactions`)을 키로 쓴다.

```go
// internal/interface/http/dto.go
type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"`
	Count        int                          `json:"count"`
}
```

`Count`는 페이지 크기가 아니라 **필터 적용 후 전체 건수**다. `internal/infrastructure/persistence/account_repository.go`의 `FindTransactions`는 `SELECT COUNT(*)`를 별도로 실행해 `total`을 구하고, `FindAll`도 동일한 패턴(`COUNT(*)` 쿼리 + `LIMIT/OFFSET` 쿼리 두 번 실행)을 따른다.

---

## 단건 조회 응답 — 범용 래퍼 없음

`GetAccountResponse`는 `{ success: true, data: {...} }` 같은 래퍼 없이 필드를 평탄하게 노출한다.

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

에러와 정상 응답의 구분은 HTTP 상태 코드가 담당한다(자세한 매핑은 [error-handling.md](error-handling.md) 참조). Go 응답 DTO에 `Success bool` 필드를 추가하지 않는다.

---

## Repository 조회 메서드 — 배열 + count 반환

`account.Repository` 인터페이스의 `FindAll`은 root의 "목록 조회는 항상 도메인 객체 배열 + count"를 그대로 따른다.

```go
// internal/domain/account/repository.go
type Repository interface {
	FindByID(ctx context.Context, accountID, ownerID string) (*Account, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Account, int, error)
	Save(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

Go의 다중 반환값(`([]*Account, int, error)`)이 TypeScript의 `Promise<{ orders: Order[]; count: number }>`와 동일한 역할을 한다 — 별도 Result 래퍼 타입 없이 언어 기능으로 표현된다.

### 단건 조회 — 별도 메서드 유지 (root와의 편차)

root 문서는 `findOne`을 따로 두지 않고 `find<Noun>s({ take: 1 })`로 통일하라고 권장하지만, 이 저장소의 Go 예제는 `FindByID`(단건, 소유자 검증 포함)와 `FindAll`(목록)을 **분리된 메서드**로 유지한다. 소유자 검증(`ownerID` 일치)이 단건 조회의 핵심 관심사이기 때문에 `FindQuery{OwnerID, Take: 1}` 형태로 통합하는 것보다 명시적인 별도 시그니처가 더 읽기 쉽다는 판단이다. 새 도메인을 작성할 때 root 방식(단일 `Find` + `take:1`)과 이 저장소 방식(`FindByID` 분리) 중 하나를 선택할 수 있으나, 한 저장소 안에서는 일관성을 유지한다. 자세한 내용은 [repository-pattern.md](repository-pattern.md) 참조.

---

## 동적 필터 조건 패턴

`internal/infrastructure/persistence/account_repository.go`의 `FindAll`은 값이 있을 때만 WHERE 조건을 추가한다.

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
	// IN 조건 — 빈 슬라이스는 조건에서 제외
}
```

Go에서는 zero value(`""`, `nil`, `len() == 0`)가 "값 없음"의 자연스러운 표현이다. TypeScript의 `if (query.field)` 가드와 동일한 의도를 Go의 zero value 체크로 구현한다.

---

## Result 객체 — 도메인 Aggregate를 직접 노출하지 않는다

`internal/application/query/`의 Query Handler는 `account.Account`(Aggregate)를 그대로 반환하지 않고, 응답 전용 Result 구조체(`GetAccountResult`, `TransactionSummary`)로 변환한다.

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
	a, err := h.repo.FindByID(ctx, q.AccountID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return &GetAccountResult{
		AccountID: a.AccountID,
		// ... Aggregate 필드를 Result 필드로 명시적으로 매핑
	}, nil
}
```

`interface/http/account_handler.go`가 다시 `GetAccountResult`를 `GetAccountResponse`(JSON DTO)로 매핑한다 — Domain → Application Result → Interface DTO 세 단계를 거치며, 각 단계가 얇은 필드 매핑이라도 레이어 경계를 명확히 유지한다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계
- [layer-architecture.md](layer-architecture.md) — Result 객체와 레이어 간 변환
- [error-handling.md](error-handling.md) — 에러와 HTTP 상태 코드 매핑
