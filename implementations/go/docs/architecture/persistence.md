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
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
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
	return accountRepo.SaveAccount(ctx, a)
})
```

이 패턴이 root의 AsyncLocalStorage 기반 TransactionManager와 동일한 역할을 한다 — 차이는 Node가 암묵적 스토리지(콜백 외부에서도 접근 가능)를 쓰는 반면, Go는 `context.Context`를 **함수 인자로 명시적으로 전달**해야 한다는 점이다(Go 관용 — context를 전역 변수나 구조체 필드에 숨겨 저장하지 않는다).

### 현재 `examples/`의 실제 코드 — 로컬 트랜잭션으로 충분한 범위까지만 구현

`internal/infrastructure/persistence/account_repository.go`의 `SaveAccount()`는 위 패턴을 쓰지 않는다. **로컬 트랜잭션을 그 함수 안에서 직접 열고 닫는다**:

```go
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
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

이것으로 충분한 이유는 `Save()` 하나가 `accounts` + `transactions` 두 테이블만 다루고, 그 범위 밖의 다른 Repository와 트랜잭션을 묶을 필요가 지금까지 없었기 때문이다. Command Handler를 전부 훑어봐도(`internal/application/command/`) 한 Handler가 두 Aggregate의 Repository에 동시에 쓰기를 호출하는 경우가 없다 — Account BC와 Card BC 사이의 상호작용은 항상 Integration Event를 통한 비동기 최종 일관성으로 처리되고([cross-domain.md](cross-domain.md)), `SuspendCardsByAccountHandler`처럼 여러 행을 갱신하는 Handler도 전부 같은 Aggregate(Card)의 Repository만 호출한다. root가 요구하는 "여러 Repository를 넘나드는 컨텍스트 기반 전파"를 만들 실제 유스케이스가 아직 없으므로, 위 목표 패턴(`database.WithTx` + `Querier`)은 만들지 않았다 — 두 번째 Aggregate가 하나의 트랜잭션으로 묶여야 하는 시나리오가 실제로 생기면 그때 이 패턴으로 리팩토링한다(YAGNI, [shared-modules.md](shared-modules.md) 참고).

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
- 조회 필터: `account_repository.go`의 `FindAccounts`가 `WHERE ... deleted_at IS NULL`을 기본으로 포함한다.
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
  0001_init.down.sql                       ← 0001_init.sql을 역순으로 되돌림
  0002_add_email_and_sent_emails.sql       ← accounts.email 컬럼 추가 + sent_emails 테이블 생성
  0002_add_email_and_sent_emails.down.sql  ← 0002_add_email_and_sent_emails.sql을 역순으로 되돌림
```

`test/account_e2e_test.go`의 `TestMain`이 컨테이너 기동 후 up 파일들을 순서대로 읽어 실행한다(`os.ReadFile(filepath.Join("..", "migrations", migration))`) — 파일명을 하드코딩한 목록으로 나열하므로 `.down.sql` 파일이 섞여 있어도 up 실행 경로에는 영향이 없다. 각 `NNNN_*.sql`에는 짝이 되는 `NNNN_*.down.sql`이 있어, 해당 up 파일이 만든 테이블/컬럼/인덱스를 생성 역순으로 제거한다 — 예를 들어 `0001_init.down.sql`은 `transactions`를 먼저 지우고(외래키가 `accounts`를 참조하므로) `accounts`를 그다음에 지운다. `golang-migrate/migrate` 같은 버전 추적 도구(`schema_migrations` 테이블)는 여전히 도입하지 않았다 — down 파일을 실제로 적용하는 것은 운영자가 수동으로 실행하는 몫이며, 이 저장소는 그 실행 메커니즘 자체를 자동화하지 않는다.

`synchronize`/`ddl-auto: update` 같은 자동 스키마 동기화에 대응하는 개념(`database/sql`은애초에 ORM이 아니므로 자동 동기화 기능 자체가 없다)은 Go에는 없다 — 항상 마이그레이션 파일을 통해서만 스키마가 바뀐다는 점에서 오히려 root 원칙(운영 환경에서는 반드시 마이그레이션 사용)을 구조적으로 지키기 쉽다.

---

## Soft Delete 필터는 harness가 자동 검사한다

`deleted_at` 컬럼을 가진 테이블(마이그레이션 SQL 기준)을 대상으로 하는 `Find*`/`FindAll`
쿼리가 `deleted_at IS NULL` 필터를 빠뜨리는 회귀는
`implementations/go/harness/soft_delete_filter.go`(`soft-delete-filter` 규칙)가 자동으로
검사한다 — `root/migrations/*.sql`(`.down.sql` 제외)에서 어떤 테이블이 실제로
`deleted_at` 컬럼을 갖는지 먼저 파악한 뒤, 그 테이블을 대상으로 하는
`internal/infrastructure/persistence/*_repository.go`의 각 조회 메서드 본문에 필터가
있는지 텍스트로 확인한다(정적 SQL 문자열이든 동적 WHERE 절 빌더의 seed 값이든 상관없다).
컬럼 자체가 없는 테이블(현재는 accounts를 제외한 모든 테이블)을 대상으로 하는 메서드는
검사 대상에서 제외된다.

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [layer-architecture.md](layer-architecture.md) — 트랜잭션 전파가 필요한 Application 레이어 조율
- [domain-events.md](domain-events.md) — Outbox 테이블도 같은 트랜잭션에 포함되어야 하는 이유
- [testing.md](testing.md) — testcontainers로 마이그레이션 파일을 실행하는 E2E 셋업
