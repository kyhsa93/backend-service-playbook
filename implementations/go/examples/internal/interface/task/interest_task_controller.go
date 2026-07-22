// Package task holds the Task Queue's Interface layer input adapters (Task
// Controllers) — just as the HTTP Handler in interface/http/ receives an HTTP
// request and delegates to an Application Service, a Task Controller receives
// a Task Queue message and calls a Command Service (docs/architecture/
// scheduling.md, "Task Controller — Interface Layer").
//
// There are only two principles: (1) delegate to the Command with no logic —
// no conditional branching or business rules here. (2) let errors propagate
// as-is — unlike an HTTP Handler, do not catch and convert them into status
// codes. taskqueue.Consumer receives that error and decides whether to delete
// the message (retry/DLQ).
package task

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/application/command"
)

type interestTaskPayload struct {
	Date string `json:"date"`
}

// InterestTaskController is the input adapter for the "account.apply-interest" Task.
type InterestTaskController struct {
	handler *command.ApplyDailyInterestHandler
}

func NewInterestTaskController(handler *command.ApplyDailyInterestHandler) *InterestTaskController {
	return &InterestTaskController{handler: handler}
}

// HandleApplyInterest satisfies the taskqueue.Handler signature. Payload
// deserialization follows the same idiom as outbox's EventHandlers
// (application/event/*_event_handler.go) — this is "translation," not
// business logic.
func (c *InterestTaskController) HandleApplyInterest(ctx context.Context, payload []byte) error {
	var p interestTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.apply-interest task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.ApplyDailyInterestCommand{Date: p.Date}) // let the error propagate as-is
}
