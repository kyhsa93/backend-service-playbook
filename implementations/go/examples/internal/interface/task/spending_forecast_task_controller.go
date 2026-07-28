package task

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/application/command"
)

type spendingForecastTaskPayload struct {
	ForecastMonth string `json:"forecastMonth"`
}

// SpendingForecastTaskController is the input adapter for the
// "account.forecast-spending" Task.
type SpendingForecastTaskController struct {
	handler *command.ForecastSpendingHandler
}

func NewSpendingForecastTaskController(handler *command.ForecastSpendingHandler) *SpendingForecastTaskController {
	return &SpendingForecastTaskController{handler: handler}
}

func (c *SpendingForecastTaskController) HandleForecastSpending(ctx context.Context, payload []byte) error {
	var p spendingForecastTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.forecast-spending task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.ForecastSpendingCommand{ForecastMonth: p.ForecastMonth}) // let the error propagate as-is
}
