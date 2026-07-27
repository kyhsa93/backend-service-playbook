package task

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/application/command"
)

type spendingAnalysisTaskPayload struct {
	AnalysisMonth string `json:"analysisMonth"`
}

// SpendingAnalysisTaskController is the input adapter for the
// "account.analyze-monthly-spending" Task.
type SpendingAnalysisTaskController struct {
	handler *command.AnalyzeMonthlySpendingHandler
}

func NewSpendingAnalysisTaskController(handler *command.AnalyzeMonthlySpendingHandler) *SpendingAnalysisTaskController {
	return &SpendingAnalysisTaskController{handler: handler}
}

func (c *SpendingAnalysisTaskController) HandleAnalyzeMonthlySpending(ctx context.Context, payload []byte) error {
	var p spendingAnalysisTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.analyze-monthly-spending task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.AnalyzeMonthlySpendingCommand{AnalysisMonth: p.AnalysisMonth}) // let the error propagate as-is
}
