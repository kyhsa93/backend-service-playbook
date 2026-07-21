// Package database는 root docs/architecture/persistence.md("트랜잭션 전파")가 요구하는
// context 기반 Unit-of-Work를 Go로 구현한다 — Node의 AsyncLocalStorage 기반
// TransactionManager, Spring의 @Transactional에 대응하는, context.Context 값 전파 기반의
// 명시적 버전이다.
//
// 한 Handler가 두 Aggregate의 Repository에 동시에 쓰기를 호출하는 경우에만 필요하다
// (go/docs/architecture/persistence.md 참고) — account/application/command/
// transfer_handler.go(계좌 간 송금)가 그 예시다: 출금 계좌 저장과 입금 계좌 저장 두 번의
// SaveAccount 호출이 원자적으로 함께 커밋되지 않으면, 출금만 반영되고 입금이 유실되는
// 실패 모드가 생긴다.
package database

import (
	"context"
	"database/sql"
	"fmt"
)

type txKey struct{}

// TxFromContext는 WithTx(또는 Manager.RunInTx)가 ctx에 담아둔 *sql.Tx를 꺼낸다.
// Repository가 "지금 앰비언트 트랜잭션에 참여해야 하는지, 아니면 스스로 트랜잭션을 열고
// 커밋해야 하는지"를 스스로 판단하는 데 쓴다(account_repository.go의 SaveAccount 참고) —
// 모든 조회/저장 경로를 무조건 QuerierFrom으로 통일하면, 앰비언트 트랜잭션이 없을 때
// 단일 Exec 문들이 개별 자동커밋되어 기존의 "계좌+거래+outbox 원자성" 보장이 조용히
// 깨진다. 그래서 커밋 주체 판단이 필요한 다중 statement Repository 메서드는 이 함수를,
// 조회처럼 단일 statement면 충분한 경로는 아래 QuerierFrom을 쓴다.
func TxFromContext(ctx context.Context) (*sql.Tx, bool) {
	tx, ok := ctx.Value(txKey{}).(*sql.Tx)
	return tx, ok
}

// WithTx는 새 트랜잭션을 시작하고 ctx에 담아 fn을 실행한다. fn이 에러 없이 끝나면
// 커밋하고, 에러를 반환하면(혹은 fn 내부에서 panic이 나면 defer Rollback으로) 롤백한다.
// 재진입 가능하다 — ctx에 이미 트랜잭션이 있으면 새로 열지 않고 그대로 재사용한다
// (nestjs TransactionManager.run()의 AsyncLocalStorage 재진입과 동일한 이유).
func WithTx(ctx context.Context, db *sql.DB, fn func(ctx context.Context) error) error {
	if _, ok := TxFromContext(ctx); ok {
		return fn(ctx)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }() // 이미 커밋됐으면 no-op

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

// QuerierFrom은 ctx에 앰비언트 트랜잭션이 있으면 그것을, 없으면 기본 db를 반환한다.
func QuerierFrom(ctx context.Context, db *sql.DB) Querier {
	if tx, ok := TxFromContext(ctx); ok {
		return tx
	}
	return db
}

// Manager는 application/command.TransactionManager 포트의 구현체다 — Application
// 레이어는 이 concrete 타입을 직접 참조하지 않고 그 포트 인터페이스로만 의존한다
// (layer-architecture.md, Go에는 DI 컨테이너가 없어 생성자 주입으로 구현).
type Manager struct {
	db *sql.DB
}

func NewManager(db *sql.DB) *Manager {
	return &Manager{db: db}
}

// RunInTx는 command.TransactionManager 시그니처를 만족한다.
func (m *Manager) RunInTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return WithTx(ctx, m.db, fn)
}
