package test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/example/account-service/internal/common"
	"github.com/example/account-service/internal/infrastructure/scheduling"
)

// getAccountTransactionTypes returns the list of all transaction types for
// the account (nil on lookup failure).
func getAccountTransactionTypes(t *testing.T, ownerID, accountID string) []string {
	t.Helper()
	resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/transactions?take=50", ownerID, nil)
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return nil
	}
	body := decodeBody(t, resp)
	raw, ok := body["transactions"].([]any)
	if !ok {
		return nil
	}
	types := make([]string, 0, len(raw))
	for _, item := range raw {
		tx, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if ty, ok := tx["type"].(string); ok {
			types = append(types, ty)
		}
	}
	return types
}

func containsString(items []string, want string) bool {
	for _, s := range items {
		if s == want {
			return true
		}
	}
	return false
}

// TestScheduledInterestPayment actually drives the entire Scheduler → Task
// Outbox → Task Queue → Task Consumer → Command Service path
// (scheduling.md) — rather than waiting for a real tick, it verifies
// deterministically by calling testInterestScheduler.EnqueueDailyInterest
// directly (the same test pattern as the scheduling.md example).
func TestScheduledInterestPayment(t *testing.T) {
	t.Run("interest_is_paid_and_an_INTEREST_entry_remains_in_the_transaction_history", func(t *testing.T) {
		owner := "interest-owner-" + common.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		today := common.Now()
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), today))

		// floor(1_000_000 * 0.0001) = 100 -> poll until the balance becomes
		// 1_000_100. Since it goes through the entire path of
		// Scheduler.EnqueueDailyInterest (Task Outbox insert) ->
		// taskqueue.Poller (1-second tick, SQS publish) -> taskqueue.Consumer
		// (long polling receive) -> InterestTaskController ->
		// ApplyDailyInterestHandler, it uses the same 30-second budget as the
		// payment/notification e2e tests.
		waitForAccountBalance(t, owner, accountID, 1_000_100)

		types := getAccountTransactionTypes(t, owner, accountID)
		require.True(t, containsString(types, "INTEREST"), "want an INTEREST transaction, got %v", types)

		// Interest payment also rides the same account-notification email
		// path (Domain Event -> Outbox -> SQS -> EventHandler) — this
		// confirms that the Task Queue (the batch itself) and the Domain
		// Event (the resulting email notification) work together within one
		// use case.
		sentEmail := waitForSentEmail(t, accountID, "InterestPaid")
		require.Equal(t, owner+"@example.com", sentEmail.Recipient)
	})

	t.Run("re_enqueueing_on_the_same_date_does_not_duplicate_the_interest_payment", func(t *testing.T) {
		owner := "interest-idem-owner-" + common.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		// The subtest above already consumed the dedup_id for "today"
		// (task_outbox.dedup_id's UNIQUE constraint), so if this one also
		// used the same date, this subtest's account would never be
		// processed — use tomorrow's date to keep the scenarios between
		// subtests separate (the idempotency verification itself remains
		// valid by enqueuing this one date twice).
		tomorrow := common.Now().AddDate(0, 0, 1)
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), tomorrow))
		waitForAccountBalance(t, owner, accountID, 1_000_100)

		// Second enqueue with the same date — the task_outbox.dedup_id
		// UNIQUE constraint means the second Task is never written in the
		// first place (scheduling.md, "Cron multi-instance safety"). Even if
		// it were written, Account.ApplyInterest itself is date-based
		// idempotent (Level 1).
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), tomorrow))

		// "It doesn't change" cannot be proven directly by polling, so allow
		// time for the asynchronous path to stably complete its round trip
		// (the same order of magnitude as the budget measured in other e2e
		// tests), then check the final value.
		time.Sleep(5 * time.Second)
		require.Equal(t, int64(1_000_100), getAccountBalance(t, owner, accountID))
	})
}

// TestScheduledCardUsageStatement verifies the same path using the card
// usage statement batch.
func TestScheduledCardUsageStatement(t *testing.T) {
	t.Run("the_monthly_statement_is_sent_and_recorded_in_the_email", func(t *testing.T) {
		owner := "statement-owner-" + common.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		cardResp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusCreated, cardResp.StatusCode)
		cardID := decodeBody(t, cardResp)["cardId"].(string)

		// The account needs a balance for the payment to go through
		// (PaymentAccountAdapter's balance check).
		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 100_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		// Create 2 COMPLETED payments (CreatePaymentHandler calls Complete()
		// immediately once checks pass, so it's synchronously COMPLETED —
		// see payment.go).
		p1 := createPayment(t, owner, cardID, 10_000)
		require.Equal(t, http.StatusCreated, p1.StatusCode)
		p2 := createPayment(t, owner, cardID, 5_000)
		require.Equal(t, http.StatusCreated, p2.StatusCode)

		// EnqueueMonthlyStatement(now) targets "the month immediately before
		// the month now falls in" as the period — since the payments just
		// created belong to this month (today), push now one month ahead so
		// that "the previous month = the month today falls in". Month
		// arithmetic starts from the first of the month: AddDate on a
		// month-end day overflows into the month after next (Jul 31 + 1
		// month = Aug 31, but Aug 31 + 1 month = Oct 1), which made this
		// test fail when CI happened to run on the 31st.
		monthStart := firstOfCurrentMonthUTC()
		triggerTime := monthStart.AddDate(0, 1, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))

		sentEmail := waitForSentEmail(t, accountID, "CardUsageStatement")
		require.Equal(t, owner+"@example.com", sentEmail.Recipient)
		require.Contains(t, sentEmail.Subject, cardID)

		period := monthStart.Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})

	t.Run("re_enqueueing_in_the_same_period_does_not_duplicate_the_statement_send", func(t *testing.T) {
		owner := "statement-idem-owner-" + common.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		cardResp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusCreated, cardResp.StatusCode)
		cardID := decodeBody(t, cardResp)["cardId"].(string)

		// The subtest above already consumed the dedup_id for the "this
		// month" period, so here we use two months ahead as the trigger to
		// target a different period ("next month") — this keeps the
		// scenarios between subtests separate, and the idempotency
		// verification itself remains valid by enqueuing this one period
		// twice. As above, month arithmetic starts from the first of the
		// month so a month-end run date can't overflow the AddDate result.
		monthStart := firstOfCurrentMonthUTC()
		triggerTime := monthStart.AddDate(0, 2, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		waitForSentEmail(t, accountID, "CardUsageStatement")

		// The task_outbox.dedup_id UNIQUE constraint means the second
		// enqueue is never even written. Even if it were,
		// Card.MarkStatementSent is idempotent for the same period (Level 1).
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		time.Sleep(5 * time.Second)

		require.Equal(t, 1, countSentEmails(t, accountID, "CardUsageStatement"))
		period := monthStart.AddDate(0, 1, 0).Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})
}

// firstOfCurrentMonthUTC returns midnight UTC on the first day of the
// current month — the safe base for month arithmetic in these tests.
func firstOfCurrentMonthUTC() time.Time {
	now := common.Now()
	return time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
}

// cardLastStatementSentMonth queries the last_statement_sent_month column
// directly — this value is an internal idempotency marker not exposed
// through the HTTP response, so it's checked directly against the DB.
func cardLastStatementSentMonth(t *testing.T, cardID string) string {
	t.Helper()
	var month string
	require.NoError(t, testDB.QueryRow(
		`SELECT COALESCE(last_statement_sent_month, '') FROM cards WHERE id = $1`, cardID,
	).Scan(&month))
	return month
}

// countSentEmails counts how many times an (accountID, eventType) combination
// was sent in sent_emails — this verifies that it wasn't sent more than once
// more precisely than findSentEmail (which only checks whether a single
// record exists).
func countSentEmails(t *testing.T, accountID, eventType string) int {
	t.Helper()
	var count int
	require.NoError(t, testDB.QueryRow(
		`SELECT COUNT(*) FROM sent_emails WHERE account_id = $1 AND event_type = $2`, accountID, eventType,
	).Scan(&count))
	return count
}

// spendingAnalysisRow mirrors the spending_analysis table's columns that the
// e2e test needs to assert on.
type spendingAnalysisRow struct {
	totalAmount             int64
	transactionCount        int
	averageAmount           int64
	changeFromPreviousMonth int
	trend                   string
}

// findSpendingAnalysis looks up the (accountId, analysisMonth) row directly
// from the DB (ok=false if it doesn't exist yet).
func findSpendingAnalysis(t *testing.T, accountID, analysisMonth string) (spendingAnalysisRow, bool) {
	t.Helper()
	var row spendingAnalysisRow
	err := testDB.QueryRow(
		`SELECT total_amount, transaction_count, average_amount, change_from_previous_month, trend
		 FROM spending_analysis WHERE account_id = $1 AND analysis_month = $2`,
		accountID, analysisMonth,
	).Scan(&row.totalAmount, &row.transactionCount, &row.averageAmount, &row.changeFromPreviousMonth, &row.trend)
	if err != nil {
		return spendingAnalysisRow{}, false
	}
	return row, true
}

// waitForSpendingAnalysis polls the spending_analysis table while waiting
// for the asynchronous Scheduler -> Task Outbox -> Task Queue -> Task
// Consumer -> AnalyzeMonthlySpendingHandler path to complete, using the same
// 30-second budget as waitForAccountBalance/waitForSentEmail.
func waitForSpendingAnalysis(t *testing.T, accountID, analysisMonth string) spendingAnalysisRow {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if row, ok := findSpendingAnalysis(t, accountID, analysisMonth); ok {
			return row
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("no spending_analysis row for account %s / month %s (timed out)", accountID, analysisMonth)
	return spendingAnalysisRow{}
}

// countSpendingAnalyses counts how many spending_analysis rows exist for an
// (accountID, analysisMonth) combination — the same "assert no duplicate"
// idiom as countSentEmails, used to confirm re-enqueueing in the same month
// does not produce a second row.
func countSpendingAnalyses(t *testing.T, accountID, analysisMonth string) int {
	t.Helper()
	var count int
	require.NoError(t, testDB.QueryRow(
		`SELECT COUNT(*) FROM spending_analysis WHERE account_id = $1 AND analysis_month = $2`, accountID, analysisMonth,
	).Scan(&count))
	return count
}

// backdateTransaction rewrites a transaction's created_at directly — the
// same technique the card-statement test uses to push payments into "last
// month," reused here (via the same account.withdraw endpoint) to age
// withdrawals into the previous calendar month without waiting for real
// time to pass.
func backdateTransaction(t *testing.T, transactionID string, createdAt time.Time) {
	t.Helper()
	_, err := testDB.Exec(`UPDATE transactions SET created_at = $1 WHERE id = $2`, createdAt, transactionID)
	require.NoError(t, err)
}

// TestScheduledMonthlySpendingAnalysis actually drives the entire
// Scheduler -> Task Outbox -> Task Queue -> Task Consumer ->
// AnalyzeMonthlySpendingHandler path (scheduling.md) — rather than waiting
// for a real Cron tick, it calls
// testSpendingAnalysisScheduler.EnqueueMonthlySpendingAnalysis directly (the
// same test pattern as TestScheduledInterestPayment/
// TestScheduledCardUsageStatement above), mirroring nestjs's
// scheduling.e2e-spec.ts "Monthly spending analysis" describe block.
func TestScheduledMonthlySpendingAnalysis(t *testing.T) {
	t.Run("an_account_with_last_months_withdrawal_history_gets_an_analysis_row_queryable_via_the_API_and_re-enqueueing_in_the_same_month_does_not_duplicate_it", func(t *testing.T) {
		owner := "spending-owner-" + common.Now().Format("150405.000000")
		acc := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := acc["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		now := common.Now()
		analysisMonth, monthStart, _ := scheduling.PreviousSpendingAnalysisPeriod(now)
		// Backdates the withdrawals into "last month," the same way the card
		// statement test backdates payments — reusing the scheduler's own
		// period computation so the analysis only lines up if it matches the
		// logic the Scheduler actually runs with.
		backdatedAt := monthStart.Add(24 * time.Hour)

		w1 := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/withdraw", owner, map[string]int{"amount": 30000})
		require.Equal(t, http.StatusCreated, w1.StatusCode)
		tx1 := decodeBody(t, w1)["transactionId"].(string)
		w2 := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/withdraw", owner, map[string]int{"amount": 20000})
		require.Equal(t, http.StatusCreated, w2.StatusCode)
		tx2 := decodeBody(t, w2)["transactionId"].(string)
		backdateTransaction(t, tx1, backdatedAt)
		backdateTransaction(t, tx2, backdatedAt)

		require.NoError(t, testSpendingAnalysisScheduler.EnqueueMonthlySpendingAnalysis(context.Background(), now))

		row := waitForSpendingAnalysis(t, accountID, analysisMonth)
		require.Equal(t, int64(50000), row.totalAmount)
		require.Equal(t, 2, row.transactionCount)
		require.Equal(t, int64(25000), row.averageAmount)
		// No prior-prior month withdrawal history exists, so the comparison
		// baseline is 0 -> the %-change is capped at 100 and the trend is
		// INCREASING (see account.NewSpendingAnalysis).
		require.Equal(t, 100, row.changeFromPreviousMonth)
		require.Equal(t, "INCREASING", row.trend)

		resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/spending-analysis?month="+analysisMonth+"-01", owner, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, float64(50000), body["totalAmount"])
		require.Equal(t, "INCREASING", body["trend"])

		// Since it's the same month's dedup_id, even if the second enqueue is
		// reprocessed, the (account_id, analysis_month) unique index + the
		// HasAnalysis precheck must prevent a duplicate row.
		require.NoError(t, testSpendingAnalysisScheduler.EnqueueMonthlySpendingAnalysis(context.Background(), now))
		time.Sleep(5 * time.Second)
		require.Equal(t, 1, countSpendingAnalyses(t, accountID, analysisMonth))
	})

	t.Run("when_no_analysis_has_been_computed_for_the_requested_month_then_returns_404", func(t *testing.T) {
		owner := "spending-404-owner-" + common.Now().Format("150405.000000")
		acc := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := acc["accountId"].(string)

		resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/spending-analysis?month=2020-01-01", owner, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "SPENDING_ANALYSIS_NOT_FOUND", body["code"])
	})
}

// spendingForecastRow mirrors the spending_forecast table's columns the e2e
// test needs to assert on.
type spendingForecastRow struct {
	predictedAmount   int64
	confidence        string
	historyMonthsUsed int
}

// findSpendingForecast looks up the (accountId, forecastMonth) row directly
// from the DB (ok=false if it doesn't exist yet).
func findSpendingForecast(t *testing.T, accountID, forecastMonth string) (spendingForecastRow, bool) {
	t.Helper()
	var row spendingForecastRow
	err := testDB.QueryRow(
		`SELECT predicted_amount, confidence, history_months_used
		 FROM spending_forecast WHERE account_id = $1 AND forecast_month = $2`,
		accountID, forecastMonth,
	).Scan(&row.predictedAmount, &row.confidence, &row.historyMonthsUsed)
	if err != nil {
		return spendingForecastRow{}, false
	}
	return row, true
}

// waitForSpendingForecast polls the spending_forecast table while waiting
// for the asynchronous Scheduler -> Task Outbox -> Task Queue -> Task
// Consumer -> ForecastSpendingHandler path to complete, using the same
// 30-second budget as waitForSpendingAnalysis.
func waitForSpendingForecast(t *testing.T, accountID, forecastMonth string) spendingForecastRow {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if row, ok := findSpendingForecast(t, accountID, forecastMonth); ok {
			return row
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("no spending_forecast row for account %s / month %s (timed out)", accountID, forecastMonth)
	return spendingForecastRow{}
}

// countSpendingForecasts counts how many spending_forecast rows exist for an
// (accountID, forecastMonth) combination — the same "assert no duplicate"
// idiom as countSpendingAnalyses.
func countSpendingForecasts(t *testing.T, accountID, forecastMonth string) int {
	t.Helper()
	var count int
	require.NoError(t, testDB.QueryRow(
		`SELECT COUNT(*) FROM spending_forecast WHERE account_id = $1 AND forecast_month = $2`, accountID, forecastMonth,
	).Scan(&count))
	return count
}

// seedSpendingAnalysis inserts a spending_analysis row directly — used by
// TestScheduledMonthlySpendingForecast to seed training history without
// separately re-deriving it via the analysis batch (that path is already
// covered by TestScheduledMonthlySpendingAnalysis above), the same
// "seed the read model directly" approach nestjs's own e2e spec takes for
// this exact scenario.
func seedSpendingAnalysis(t *testing.T, accountID, analysisMonth string, totalAmount int64) {
	t.Helper()
	_, err := testDB.Exec(
		`INSERT INTO spending_analysis (id, account_id, analysis_month, total_amount, transaction_count, average_amount, change_from_previous_month, trend)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
		common.NewID(), accountID, analysisMonth, totalAmount, 1, totalAmount, 0, "STABLE",
	)
	require.NoError(t, err)
}

// TestScheduledMonthlySpendingForecast actually drives the entire
// Scheduler -> Task Outbox -> Task Queue -> Task Consumer ->
// ForecastSpendingHandler path (scheduling.md) — rather than waiting for a
// real Cron tick, it calls
// testSpendingForecastScheduler.EnqueueMonthlySpendingForecast directly,
// mirroring nestjs's scheduling.e2e-spec.ts "Monthly spending forecast"
// describe block.
func TestScheduledMonthlySpendingForecast(t *testing.T) {
	t.Run("an_account_with_3_months_of_spending_analysis_history_gets_a_trained_forecast_row_queryable_via_the_API_and_re-enqueueing_in_the_same_month_does_not_duplicate_it", func(t *testing.T) {
		owner := "forecast-owner-" + common.Now().Format("150405.000000")
		acc := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := acc["accountId"].(string)

		now := common.Now()
		forecastMonth := now.Format("2006-01")

		// Seeds 3 months of spending_analysis history directly — a perfectly
		// linear trend (10000, 20000, 30000) that extrapolates exactly to
		// 40000 with full confidence (see SpendingForecastModelImpl).
		amounts := []int64{10000, 20000, 30000}
		for monthsAgo := 3; monthsAgo >= 1; monthsAgo-- {
			monthDate := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC).AddDate(0, -monthsAgo, 0)
			analysisMonth := monthDate.Format("2006-01")
			seedSpendingAnalysis(t, accountID, analysisMonth, amounts[3-monthsAgo])
		}

		require.NoError(t, testSpendingForecastScheduler.EnqueueMonthlySpendingForecast(context.Background(), now))

		row := waitForSpendingForecast(t, accountID, forecastMonth)
		require.Equal(t, int64(40000), row.predictedAmount)
		require.Equal(t, "HIGH", row.confidence)
		require.Equal(t, 3, row.historyMonthsUsed)

		resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/spending-forecast?month="+forecastMonth+"-01", owner, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, float64(40000), body["predictedAmount"])
		require.Equal(t, "HIGH", body["confidence"])

		// Since it's the same month's dedup_id, even if the second enqueue is
		// reprocessed, the (account_id, forecast_month) unique index + the
		// HasForecast precheck must prevent a duplicate row.
		require.NoError(t, testSpendingForecastScheduler.EnqueueMonthlySpendingForecast(context.Background(), now))
		time.Sleep(5 * time.Second)
		require.Equal(t, 1, countSpendingForecasts(t, accountID, forecastMonth))
	})

	t.Run("an_account_younger_than_3_months_of_spending_analysis_history_gets_no_forecast_and_the_API_returns_404", func(t *testing.T) {
		owner := "forecast-404-owner-" + common.Now().Format("150405.000000")
		acc := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := acc["accountId"].(string)

		now := common.Now()
		forecastMonth := now.Format("2006-01")

		require.NoError(t, testSpendingForecastScheduler.EnqueueMonthlySpendingForecast(context.Background(), now))
		time.Sleep(5 * time.Second)

		resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/spending-forecast?month="+forecastMonth+"-01", owner, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "SPENDING_FORECAST_NOT_FOUND", body["code"])
	})
}
