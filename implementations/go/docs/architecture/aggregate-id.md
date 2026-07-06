# Aggregate ID 생성 (Go)

원칙은 루트 [aggregate-id.md](../../../../docs/architecture/aggregate-id.md)를 그대로 따른다. ID는 **Domain 레이어(Aggregate 생성자)** 에서 서버가 생성하며, 형식은 **UUID v4에서 하이픈을 제거한 32자리 hex 문자열**이다.

```go
"550e8400e29b41d4a716446655440000"   // 올바른 방식 — 32자리, 하이픈 없음
"550e8400-e29b-41d4-a716-446655440000"  // 잘못된 방식 — 하이픈 포함
1, 2, 3                                  // 잘못된 방식 — auto-increment 숫자
```

---

## ID 생성 유틸

Go 표준 라이브러리에는 UUID 생성기가 없으므로 `github.com/google/uuid` 같은 최소 의존성을 사용한다. 하이픈 제거는 `strings.ReplaceAll`로 직접 처리한다.

```go
// internal/common/id.go
package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID는 UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환한다.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
```

---

## 현재 예제의 알려진 편차 — `uuid.NewString()`을 하이픈 포함 그대로 사용

`internal/domain/account/account.go`의 `New()`와 `internal/domain/account/transaction.go`의 `newTransaction()`은 현재 `uuid.NewString()`을 하이픈 제거 없이 그대로 사용한다.

```go
// internal/domain/account/account.go — 현재 코드 (편차)
func New(ownerID, email, currency string) *Account {
	now := time.Now()
	a := &Account{
		AccountID: uuid.NewString(),  // "550e8400-e29b-41d4-a716-446655440000" — 하이픈 포함
		...
	}
}
```

이는 루트 문서가 명시적으로 금지하는 "하이픈 포함 UUID" 형식이다. 새 도메인을 작성할 때는 이 문서의 `common.NewID()` 패턴(하이픈 제거 32자리 hex)을 따르고, `account.go`/`transaction.go`를 손대는 리팩터링을 할 기회가 생기면 `uuid.NewString()` 호출을 `common.NewID()`로 교체해 루트 규칙에 맞춘다. 이 문서는 가이드이며 이번 패스에서 `examples/` 코드 자체는 수정하지 않는다.

---

## Aggregate 생성자에서 사용

신규 생성과 DB 복원을 별도 함수로 분리하는 것이 Go 컨벤션이다 — 이 저장소는 생성자 `New(...)`와 복원용 `Reconstitute(...)`를 분리해서 이 원칙을 이미 구현하고 있다.

```go
// internal/domain/account/account.go
func New(ownerID, email, currency string) *Account {
	return &Account{
		AccountID: common.NewID(), // 신규 생성 — ID를 새로 발급
		OwnerID:   ownerID,
		// ...
	}
}

func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	return &Account{
		AccountID: accountID, // DB 복원 — 기존 ID를 그대로 사용
		OwnerID:   ownerID,
		// ...
	}
}
```

- **신규 생성**: `New()`가 ID를 새로 발급한다. 클라이언트가 제공한 ID를 받지 않는다 — `New()`의 파라미터 목록에 ID가 없다는 점 자체가 이 규칙을 코드로 강제한다.
- **DB 복원**: `internal/infrastructure/persistence/account_repository.go`의 `FindByID`/`FindAll`이 DB row에서 읽은 `id` 컬럼 값을 `Reconstitute()`에 전달한다. Repository는 ID를 새로 발급하지 않는다.

---

## Repository 구현체에서 ID 처리

Repository는 Aggregate가 이미 가진 ID를 그대로 저장한다. `internal/infrastructure/persistence/account_repository.go`의 `Save()`:

```go
_, err = tx.ExecContext(ctx,
	`INSERT INTO accounts (id, owner_id, email, amount, currency, status, updated_at)
	 VALUES ($1, $2, $3, $4, $5, $6, NOW())
	 ON CONFLICT (id) DO UPDATE SET amount = EXCLUDED.amount, status = EXCLUDED.status, updated_at = NOW()`,
	a.AccountID, a.OwnerID, a.Email, a.Balance.Amount, a.Balance.Currency, string(a.Status),
)
```

`a.AccountID`는 이미 Domain 레이어에서 확정된 값이다 — DB의 `SERIAL`/`AUTO_INCREMENT`나 `RETURNING id` 같은 메커니즘으로 새로 발급하지 않는다.

---

## 하위 Entity ID

`Transaction`(Entity)도 Aggregate Root와 동일하게 문자열 ID를 사용한다(`internal/domain/account/transaction.go`의 `newTransaction()`). 하위 Entity를 생성하는 시점 역시 Domain 레이어이며, Repository나 DB가 개입하지 않는다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 생성자 패턴, `New`/`Reconstitute` 분리
- [repository-pattern.md](repository-pattern.md) — Repository의 저장/복원 책임
- [persistence.md](persistence.md) — ID 컬럼 타입(`VARCHAR(36)`)과 스키마
