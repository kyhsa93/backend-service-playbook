package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/common"
)

// Publisher는 Application EventHandler가 만든 Integration Event를 Outbox 테이블에 새 행으로
// 적재한다. Writer가 Aggregate의 Domain Event를 Repository.Save 트랜잭션 안에서 적재하는 것과
// 달리, Publisher는 이미 커밋된 이벤트를 Drain하는 도중(Relay 내부)에 호출되므로 자체 커넥션으로
// 단일 행을 insert한다(auto-commit).
//
// 적재된 행은 Relay가 다음 Drain 패스에서 event_type(예: "account.suspended.v1")에 매핑된
// 핸들러로 전달한다 — Account가 Card를 직접 호출하지 않고 Outbox를 매개로 느슨하게 연결된다.
type Publisher struct {
	db *sql.DB
}

func NewPublisher(db *sql.DB) *Publisher {
	return &Publisher{db: db}
}

// Publish는 eventName을 event_type으로, payload의 JSON 직렬화 결과를 payload로 하는 Outbox
// 행을 하나 insert한다. event.IntegrationPublisher 포트를 구조적으로 만족한다.
func (p *Publisher) Publish(ctx context.Context, eventName string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal integration event %s: %w", eventName, err)
	}
	if _, err := p.db.ExecContext(ctx,
		`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
		common.NewID(), eventName, body,
	); err != nil {
		return fmt.Errorf("publish integration event %s: %w", eventName, err)
	}
	return nil
}
