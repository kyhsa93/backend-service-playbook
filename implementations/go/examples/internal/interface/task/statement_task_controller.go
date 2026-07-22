package task

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/application/command"
)

type statementTaskPayload struct {
	Period string `json:"period"`
}

// StatementTaskController is the input adapter for the "card.send-usage-statement" Task.
type StatementTaskController struct {
	handler *command.SendCardUsageStatementHandler
}

func NewStatementTaskController(handler *command.SendCardUsageStatementHandler) *StatementTaskController {
	return &StatementTaskController{handler: handler}
}

func (c *StatementTaskController) HandleSendStatement(ctx context.Context, payload []byte) error {
	var p statementTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal card.send-usage-statement task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.SendCardUsageStatementCommand{Period: p.Period}) // let the error propagate as-is
}
