package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"

	"github.com/example/account-service/internal/common"
	"github.com/example/account-service/internal/domain/account"
)

// Writer loads Domain Events an Aggregate accumulated while running domain
// methods into the outbox table. It must be called inside the
// Repository's save transaction (the same *sql.Tx) for the account state
// change and the event load to commit atomically (avoiding dual-write).
type Writer struct{}

func NewWriter() *Writer {
	return &Writer{}
}

// SaveAll inserts the events into the outbox table within the given
// transaction. The caller (Repository) is responsible for committing/
// rolling back the transaction — this method does not commit.
func (w *Writer) SaveAll(ctx context.Context, tx *sql.Tx, events []account.DomainEvent) error {
	for _, event := range events {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			common.NewID(), eventTypeName(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}

// eventTypeName uses the event struct's type name as-is for the event_type
// column value (e.g. account.MoneyDeposited{} → "MoneyDeposited"). The
// Relay looks up its handler using this string.
func eventTypeName(event account.DomainEvent) string {
	return reflect.TypeOf(event).Name()
}
