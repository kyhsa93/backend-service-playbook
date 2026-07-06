# 영속성 패턴 (Go) — 트랜잭션, Soft Delete, 마이그레이션

원칙은 루트 [persistence.md](../../../../docs/architecture/persistence.md)를 따른다. root는 트랜잭션 전파를 위해 언어별 컨텍스트-로컬 저장소(Node의 AsyncLocalStorage, Go는 `context.Context`)를 명시적으로 지목한다 — 이 문서는 Go에서 그것이 실제로 어떻게 구현되어야 하는지, 그리고 **이 저장소의 `examples/`가 실제로 무엇을 하고 있는지**를 정확히 구분해서 설명한다.

---

## 트랜잭션 전파 — root가 요구하는 패턴 vs 현재 코드

### root 원칙: `context.Context`로 암묵 전파

Go의 `context.Context`는 API 경계를 넘나드는 유일한 표준 값 전파 채널이다. TransactionManager 패턴을 Go로 옮기면 이런 모습이 된다:

```go
// internal/infrastructure/database/transaction.go — 목표 패턴 (아직 이 저장소엔 없음)
package database

import (
	"context"
	"database/sql"
)

type txKey struct{}

// WithTx는 새 트랜잭션을 시작하고 ctx에 담아 fn을 실행한다.
// fn이 에러 없이 끝나면 커밋하고, 에러를 반환하면 롤백한다.
func WithTx(ctx context.Context, db *sql.DB, fn func(ctx context.Context) error) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	ctxWithTx := context.WithValue(ctx, txKey{}, tx)
	if err := fn(ctxWithTx); err != nil {
		return err
	}
	return tx.Commit()
}

// Querier는 *sql.DB와 *sql.Tx가 공통으로 만족하는 최소 인터페이스다.
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// querier는 ctx에 트랜잭션이 있으면 그것을, 없으면 기본 db를 반환한다.
func querier(ctx context.Context, db *sql.DB) Querier {
	if tx, ok := ctx.Value(txKey{}).(*sql.Tx); ok {
		return tx
	}
	return db
}
```

Repository 구현체는 `db.BeginTx`를 직접 호출하지 않고 `querier(ctx, r.db)`로 현재 트랜잭션 컨텍스트를 받는다:

```go
func (r *AccountRepository) Save(ctx context.Context, a *account.Account) error {
	q := querier(ctx, r.db)
	_, err := q.ExecContext(ctx, `INSERT INTO accounts (...) VALUES (...) ON CONFLICT ...`, ...)
	return err
}
```

여러 Repository를 하나의 트랜잭션으로 묶고 싶은 Application 핸들러는 `database.WithTx`로 감싼다:

```go
err := database.WithTx(ctx, db, func(ctx context.Context) error {
	if err := paymentRepo.DeletePaymentMethods(ctx, accountID); err != nil {
		return err
	}
	return accountRepo.Save(ctx, a)
})
```

이 패턴이 root의 AsyncLocalStorage 기반 TransactionManager와 동일한 역할을 한다 — 차이는 Node가 암묵적 스토리지(콜백 외부에서도 접근 가능)를 쓰는 반면, Go는 `context.Context`를 **함수 인자로 명시적으로 전달**해야 한다는 점이다(Go 관용 — context를 전역 변수나 구조체 필드에 숨겨 저장하지 않는다).

### 현재 `examples/`의 실제 코드 — 알려진 격차

`internal/infrastructure/persistence/account_repository.go`의 `Save()`는 위 패턴을 쓰지 않는다. **로컬 트랜잭션을 그 함수 안에서 직접 열고 닫는다**:

```go
func (r *AccountRepository) Save(ctx context.Context, a *account.Account) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx, `INSERT INTO accounts (...) VALUES (...) ON CONFLICT ...`, ...)
	// ... transactions 테이블도 같은 tx로 insert
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearTransactions()
	return nil
}
```

이것으로 충분한 이유는 `Save()` 하나가 `accounts` + `transactions` 두 테이블만 다루고, 그 범위 밖의 다른 Repository와 트랜잭션을 묶을 필요가 지금까지 없었기 때문이다. **하지만 root가 요구하는 "여러 Repository를 넘나드는 컨텍스트 기반 전파"는 이 저장소 어디에도 구현되어 있지 않다** — 이후 두 번째 Aggregate(예: Payment)가 추가되어 Account와 하나의 트랜잭션으로 묶여야 하는 시나리오가 생기면 위의 목표 패턴으로 리팩토링이 필요하다.

---

## Entity 공통 컬럼 — `created_at` / `updated_at` / `deleted_at`

`migrations/0001_init.sql`의 `accounts`, `transactions` 테이블 모두 이 세 컬럼을 갖는다:

```sql
CREATE TABLE accounts (
  id          VARCHAR(36)  PRIMARY KEY,
  owner_id    VARCHAR(36)  NOT NULL,
  amount      BIGINT       NOT NULL DEFAULT 0,
  currency    VARCHAR(3)   NOT NULL DEFAULT 'KRW',
  status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP    NULL
);
```

Go에는 ORM 베이스 클래스/믹스인이 없으므로(공용 struct embedding으로 흉내낼 수는 있지만 이 저장소는 그렇게 하지 않는다), `Account` 구조체가 `CreatedAt`/`UpdatedAt time.Time` 필드를 직접 갖고, `DeletedAt`은 DB 컬럼에는 있지만 Go 쪽 `Account` 구조체에는 아직 매핑되어 있지 않다(soft delete가 실제로 트리거되는 유스케이스가 없기 때문 — 아래 참고).

---

## Soft Delete

- 스키마: `deleted_at TIMESTAMP NULL` — `NULL`이면 활성, non-NULL이면 삭제됨.
- 조회 필터: `account_repository.go`의 `FindByID`/`FindAll` 모두 `WHERE ... deleted_at IS NULL`을 기본으로 포함한다.
- **알려진 격차**: `deleted_at`을 실제로 채우는 `DELETE`/soft-delete 유스케이스가 코드에 없다. `Account.Close()`는 `Status`를 `StatusClosed`로 바꿀 뿐 `deleted_at`을 건드리지 않는다 — "계좌 종료"와 "행 삭제"는 이 도메인에서 별개 개념이다. 만약 향후 계좌를 완전히 지우는 유스케이스(예: 규정 준수를 위한 개인정보 삭제)가 생기면:

```go
func (r *AccountRepository) SoftDelete(ctx context.Context, accountID string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE accounts SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`, accountID)
	return err
}
```

hard delete(`DELETE FROM accounts ...`)는 사용하지 않는다.

---

## 마이그레이션

순번 SQL 파일을 그대로 실행하는 방식이다(`golang-migrate` 같은 도구 없이 순수 SQL):

```
migrations/
  0001_init.sql                            ← accounts, transactions 테이블 생성
  0002_add_email_and_sent_emails.sql       ← accounts.email 컬럼 추가 + sent_emails 테이블 생성
```

`test/account_e2e_test.go`의 `TestMain`이 컨테이너 기동 후 이 파일들을 순서대로 읽어 실행한다(`os.ReadFile(filepath.Join("..", "migrations", migration))`). **알려진 격차**: 각 마이그레이션에 대응하는 롤백(`down`) 스크립트가 없다 — `0001_init.down.sql` 같은 파일이 없으므로 프로덕션에서 마이그레이션을 되돌리려면 수동으로 반대 SQL을 작성해야 한다. `golang-migrate/migrate` 같은 라이브러리를 도입하면 `0001_init.up.sql`/`0001_init.down.sql` 쌍 관리와 마이그레이션 버전 추적(`schema_migrations` 테이블)을 표준화할 수 있다 — 이번 문서화 패스에서는 도구 자체를 도입하지 않았다.

`synchronize`/`ddl-auto: update` 같은 자동 스키마 동기화에 대응하는 개념(`database/sql`은애초에 ORM이 아니므로 자동 동기화 기능 자체가 없다)은 Go에는 없다 — 항상 마이그레이션 파일을 통해서만 스키마가 바뀐다는 점에서 오히려 root 원칙(운영 환경에서는 반드시 마이그레이션 사용)을 구조적으로 지키기 쉽다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [layer-architecture.md](layer-architecture.md) — 트랜잭션 전파가 필요한 Application 레이어 조율
- [domain-events.md](domain-events.md) — Outbox 테이블도 같은 트랜잭션에 포함되어야 하는 이유
- [testing.md](testing.md) — testcontainers로 마이그레이션 파일을 실행하는 E2E 셋업
