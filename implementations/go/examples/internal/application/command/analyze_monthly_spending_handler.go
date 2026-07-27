package command

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/account"
)

// AnalyzeMonthlySpendingCommand is the input SpendingAnalysisTaskController
// (interface/task/) builds by deserializing the Task Queue message payload.
// AnalysisMonth is in "2006-01" format, and
// SpendingAnalysisScheduler.EnqueueMonthlySpendingAnalysis sends "last
// month" as of enqueue time as-is in the payload — the same reason as
// ApplyDailyInterestCommand's Date: if the Consumer recomputed "which month"
// from the actual clock at processing time, a delayed/backlogged run could
// analyze the wrong month.
//
// Unlike nestjs's AnalyzeMonthlySpendingCommand (which carries all 4 boundary
// dates through the payload explicitly), only the identifying period string
// is carried here — spendingAnalysisPeriodRange below re-derives the 4
// boundaries purely from that fixed string, never from the processing-time
// clock, so the same safety property holds. This mirrors the existing Go
// idiom already used one file over: SendCardUsageStatementCommand carries
// only Period, and periodRange derives its [from, to) range from it.
type AnalyzeMonthlySpendingCommand struct {
	AnalysisMonth string
}

// spendingAnalysisBatchSize uses 500 for the same reason as
// interestBatchSize/cardStatementBatchSize.
const spendingAnalysisBatchSize = 500

// AnalyzeMonthlySpendingHandler is a system-triggered use case driven by the
// Task Queue — the ETL, in full: Extract (paginate every ACTIVE account,
// summarize its and the prior month's WITHDRAWAL transactions), Transform
// (account.NewSpendingAnalysis's %-change/trend calculation), Load (one row
// per account per month into spending_analysis). The output is a queryable
// read-model row, not a file — the value is precomputing an aggregate a
// client would otherwise have to re-derive from potentially many raw
// Transaction rows on every request.
type AnalyzeMonthlySpendingHandler struct {
	repo         account.Repository
	analysisRepo account.SpendingAnalysisRepository
}

func NewAnalyzeMonthlySpendingHandler(repo account.Repository, analysisRepo account.SpendingAnalysisRepository) *AnalyzeMonthlySpendingHandler {
	return &AnalyzeMonthlySpendingHandler{repo: repo, analysisRepo: analysisRepo}
}

// Handle saves and returns immediately per account — there is no Domain
// Event/Outbox involvement in this use case at all (SpendingAnalysis is a
// plain read-model row, not an Aggregate that raises events), so unlike
// ApplyDailyInterestHandler this has nothing to do with the Outbox path.
func (h *AnalyzeMonthlySpendingHandler) Handle(ctx context.Context, cmd AnalyzeMonthlySpendingCommand) error {
	monthStart, monthEnd, previousMonthStart, previousMonthEnd, err := spendingAnalysisPeriodRange(cmd.AnalysisMonth)
	if err != nil {
		return fmt.Errorf("analyze monthly spending: %w", account.ErrInvalidAnalysisMonth)
	}

	for page := 0; ; page++ {
		accounts, total, err := h.repo.FindAccounts(ctx, account.FindQuery{
			Status: []account.Status{account.StatusActive},
			Take:   spendingAnalysisBatchSize,
			Page:   page,
		})
		if err != nil {
			return fmt.Errorf("analyze monthly spending: find accounts: %w", err)
		}

		for _, a := range accounts {
			alreadyAnalyzed, err := h.analysisRepo.HasAnalysis(ctx, a.AccountID, cmd.AnalysisMonth)
			if err != nil {
				return fmt.Errorf("analyze monthly spending: has analysis: %w", err)
			}
			if alreadyAnalyzed {
				continue // Skip: this account was already analyzed for this month.
			}

			current, err := h.repo.SummarizeTransactions(ctx, account.SummarizeTransactionsQuery{
				AccountID:   a.AccountID,
				Type:        []account.TransactionType{account.TransactionTypeWithdrawal},
				CreatedFrom: monthStart,
				CreatedTo:   monthEnd,
			})
			if err != nil {
				return fmt.Errorf("analyze monthly spending: summarize current month: %w", err)
			}
			previous, err := h.repo.SummarizeTransactions(ctx, account.SummarizeTransactionsQuery{
				AccountID:   a.AccountID,
				Type:        []account.TransactionType{account.TransactionTypeWithdrawal},
				CreatedFrom: previousMonthStart,
				CreatedTo:   previousMonthEnd,
			})
			if err != nil {
				return fmt.Errorf("analyze monthly spending: summarize previous month: %w", err)
			}

			analysis := account.NewSpendingAnalysis(a.AccountID, cmd.AnalysisMonth, current.TotalAmount, current.Count, previous.TotalAmount)
			if err := h.analysisRepo.SaveAnalysis(ctx, analysis); err != nil {
				return fmt.Errorf("analyze monthly spending: save analysis: %w", err)
			}
		}

		if len(accounts) == 0 || (page+1)*spendingAnalysisBatchSize >= total {
			break
		}
	}
	return nil
}

// spendingAnalysisPeriodRange derives the [monthStart, monthEnd) and
// [previousMonthStart, previousMonthEnd) UTC calendar boundaries purely from
// analysisMonth ("2006-01") — mirrors nestjs's
// computePreviousSpendingAnalysisPeriod's calendar math (monthStart is the
// 1st of analysisMonth, monthEnd is the 1st of the next month,
// previousMonthStart is the 1st of the month before analysisMonth, and
// previousMonthEnd is monthStart), just re-derived from the fixed period
// string instead of "now" — the same trade-off periodRange (in
// send_card_usage_statement_handler.go) already makes.
func spendingAnalysisPeriodRange(analysisMonth string) (monthStart, monthEnd, previousMonthStart, previousMonthEnd time.Time, err error) {
	monthStart, err = time.Parse("2006-01", analysisMonth)
	if err != nil {
		return time.Time{}, time.Time{}, time.Time{}, time.Time{}, err
	}
	monthEnd = monthStart.AddDate(0, 1, 0)
	previousMonthStart = monthStart.AddDate(0, -1, 0)
	previousMonthEnd = monthStart
	return monthStart, monthEnd, previousMonthStart, previousMonthEnd, nil
}
