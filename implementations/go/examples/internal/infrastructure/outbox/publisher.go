package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/common"
)

// Publisher loads an Integration Event built by an Application EventHandler
// into the Outbox table as a new row. Unlike Writer, which loads an
// Aggregate's Domain Events inside the Repository.Save transaction,
// Publisher is called while draining an already-committed event (inside
// the Relay), so it inserts a single row using its own connection
// (auto-commit).
//
// The loaded row is delivered by the Relay on the next Drain pass to the
// handler mapped to its event_type (e.g. "account.suspended.v1") — Account
// doesn't call Card directly; they're loosely coupled via the Outbox.
type Publisher struct {
	db *sql.DB
}

func NewPublisher(db *sql.DB) *Publisher {
	return &Publisher{db: db}
}

// Publish inserts a single Outbox row with eventName as event_type and the
// JSON-serialized payload as payload. It structurally satisfies the
// event.IntegrationPublisher port.
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
