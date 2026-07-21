# 영속성 패턴 (Go) — 트랜잭션, Soft Delete, 마이그레이션

원칙은 루트 [persistence.md](../../../../docs/architecture/persistence.md)를 따른다. root는 트랜잭션 전파를 위해 언어별 컨텍스트-로컬 저장소(Node의 AsyncLocalStorage, Go는 `context.Context`)를 명시적으로 지목한다 — 이 문서는 Go에서 그것이 실제로 어떻게 구현되어야 하는지, 그리고 **이 저장소의 `examples/`가 실제로 무엇을 하고 있는지**를 정확히 구분해서 설명한다.

---

## 트랜잭션 전파 — `context.Context` 기반, `internal/infrastructure/database/`에 실제로 구현됨

### root 원칙: `context.Context`로 암묵 전파

Go의 `context.Context`는 API 경계를 넘나드는 유일한 표준 값 전파 채널이다. root의 AsyncLocalStorage 기반 TransactionManager에 대응하는 실제 구현이 `internal/infrastructure/database/transaction.go`에 있다:

```go
// internal/infrastructure/database/transaction.go — 실제 코드
package database

type txKey struct{}

// TxFromContext는 WithTx(또는 Manager.RunInTx)가 ctx에 담아둔 *sql.Tx를 꺼낸다.
// Repository가 "지금 앰비언트 트랜잭션에 참여해야 하는지, 아니면 스스로 트랜잭션을
// 열고 커밋해야 하는지"를 스스로 판단하는 데 쓴다.
func TxFromContext(ctx context.Context) (*sql.Tx, bool) {
	tx, ok := ctx.Value(txKey{}).(*sql.Tx)
	return tx, ok
}

// WithTx는 새 트랜잭션을 시작하고 ctx에 담아 fn을 실행한다 — 재진입 가능하다
// (ctx에 이미 트랜잭션이 있으면 새로 열지 않고 재사용).
func WithTx(ctx context.Context, db *sql.DB, fn func(ctx context.Context) error) error {
	if _, ok := TxFromContext(ctx); ok {
		return fn(ctx)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if err := fn(context.WithValue(ctx, txKey{}, tx)); err != nil {
		return err
	}
	return tx.Commit()
}

// Querier는 *sql.DB와 *sql.Tx가 공통으로 만족하는 최소 인터페이스다 — 조회 경로처럼
// 커밋 주체 판단이 필요 없는 단일 statement 호출부가 쓴다.
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func QuerierFrom(ctx context.Context, db *sql.DB) Querier {
	if tx, ok := TxFromContext(ctx); ok {
		return tx
	}
	return db
}

// Manager는 application/command.TransactionManager 포트의 구현체다.
type Manager struct{ db *sql.DB }

func NewManager(db *sql.DB) *Manager { return &Manager{db: db} }

func (m *Manager) RunInTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return WithTx(ctx, m.db, fn)
}
```

이 패턴이 root의 AsyncLocalStorage 기반 TransactionManager와 동일한 역할을 한다 — 차이는 Node가 암묵적 스토리지(콜백 외부에서도 접근 가능)를 쓰는 반면, Go는 `context.Context`를 **함수 인자로 명시적으로 전달**해야 한다는 점이다(Go 관용 — context를 전역 변수나 구조체 필드에 숨겨 저장하지 않는다).

### `SaveAccount`가 커밋 주체를 스스로 판단한다 — `QuerierFrom`을 무작정 쓰지 않는 이유

`internal/infrastructure/persistence/account_repository.go`의 `SaveAccount()`는 조회 경로처럼 무조건 `QuerierFrom`을 호출하지 **않는다**. 대신 `TxFromContext`로 앰비언트 트랜잭션이 있는지 직접 확인해 커밋 책임을 스스로 결정한다:

```go
// 실제 코드
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
	if tx, ok := database.TxFromContext(ctx); ok {
		// 앰비언트 트랜잭션에 참여만 한다 — 커밋은 호출부(TransferHandler 등)의 몫.
		if err := r.saveAccount(ctx, tx, a); err != nil {
			return err
		}
		a.ClearTransactions()
		a.ClearEvents()
		return nil
	}

	// 앰비언트 트랜잭션이 없다 — 기존의 모든 단독 호출부(deposit/withdraw 등)와
	// 동일하게 스스로 열고 커밋까지 책임진다.
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if err := r.saveAccount(ctx, tx, a); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}

// saveAccount는 계좌+거래+Outbox 저장 SQL을 공유하는 사설 헬퍼다 — 양쪽 경로가
// 정확히 같은 SQL을 실행하므로, 앰비언트 트랜잭션이 없는 기존 호출부의 동작은
// 이 리팩토링으로 한 글자도 바뀌지 않는다.
func (r *AccountRepository) saveAccount(ctx context.Context, tx *sql.Tx, a *account.Account) error { /* ... */ }
```

**왜 `QuerierFrom(ctx, r.db)`를 모든 문에 무작정 쓰지 않는가**: `outbox.Writer.SaveAll`이 `*sql.Tx`로 하드타입돼 있고, 더 중요하게는 `SaveAccount`가 지금까지 `accounts`+`transactions`+Outbox 3개 테이블 저장을 자기 로컬 트랜잭션으로 원자적으로 묶어 왔다 — `QuerierFrom`이 앰비언트 트랜잭션 없을 때 `*sql.DB`를 돌려주면 각 `ExecContext`가 개별 자동커밋되어 그 원자성이 조용히 깨진다. `TxFromContext`로 커밋 주체를 스스로 판단하는 방식이 이 회귀를 피한다.

### 실사용처 — `TransferHandler`(계좌 간 송금)

여러 Repository(정확히는 같은 `AccountRepository`의 서로 다른 두 Account 인스턴스)를 하나의 트랜잭션으로 묶어야 하는 대표 유스케이스가 계좌 간 송금이다 — 출금 계좌 저장과 입금 계좌 저장이 각자 커밋되면 "출금은 반영됐는데 입금은 유실됨" 실패 모드가 생긴다:

```go
// internal/application/command/transfer_handler.go — 실제 코드
func (h *TransferHandler) Handle(ctx context.Context, cmd TransferCommand) (*TransferResult, error) {
	// ... source/target 로드, eligibility 판단, Withdraw/Deposit 호출 ...

	if err := h.tx.RunInTx(ctx, func(ctx context.Context) error {
		if err := h.repo.SaveAccount(ctx, source); err != nil {
			return err
		}
		return h.repo.SaveAccount(ctx, target)
	}); err != nil {
		return nil, err
	}
	// ...
}
```

`main.go`가 `database.NewManager(db)`를 만들어 `command.TransactionManager` 포트로 주입한다 — Application 레이어는 concrete `*database.Manager`가 아니라 그 포트 인터페이스로만 의존한다(layer-architecture.md).

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

`synchronize`/`ddl-auto: update` 같은 자동 스키마 동기화에 대응하는 개념(`database/sql`은 애초에 ORM이 아니므로 자동 동기화 기능 자체가 없다)은 Go에는 없다 — 항상 마이그레이션 파일을 통해서만 스키마가 바뀐다는 점에서 오히려 root 원칙(운영 환경에서는 반드시 마이그레이션 사용)을 구조적으로 지키기 쉽다. 그래서 harness에는 `no-orm-autosync-in-prod-config`에 해당하는 규칙이 없다 — 검사할 ORM 자동 동기화 설정 자체가 이 스택에 존재하지 않기 때문이다(`implementations/go/harness/README.md`의 "구현하지 않은 규칙" 참고).

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
