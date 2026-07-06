package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"

	"github.com/google/uuid"

	"github.com/example/account-service/internal/domain/account"
)

// Writer는 Aggregate가 도메인 메서드 실행 중 쌓아둔 Domain Event를 outbox 테이블에
// 적재한다. Repository의 저장 트랜잭션 내부(같은 *sql.Tx)에서 호출되어야
// 계좌 상태 변경과 이벤트 적재가 원자적으로 커밋된다(dual-write 회피).
type Writer struct{}

func NewWriter() *Writer {
	return &Writer{}
}

// SaveAll은 주어진 트랜잭션 안에서 이벤트들을 outbox 테이블에 insert 한다.
// 호출부(Repository)가 트랜잭션을 커밋/롤백할 책임을 진다 — 이 메서드는 커밋하지 않는다.
func (w *Writer) SaveAll(ctx context.Context, tx *sql.Tx, events []account.DomainEvent) error {
	for _, event := range events {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			uuid.NewString(), eventTypeName(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}

// eventTypeName은 이벤트 struct의 타입명을 그대로 event_type 컬럼 값으로 쓴다
// (예: account.MoneyDeposited{} → "MoneyDeposited"). Relay가 이 문자열로 핸들러를 찾는다.
func eventTypeName(event account.DomainEvent) string {
	return reflect.TypeOf(event).Name()
}
