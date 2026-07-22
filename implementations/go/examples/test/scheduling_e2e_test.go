package test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
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
		owner := "interest-owner-" + time.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		today := time.Now().UTC()
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
		owner := "interest-idem-owner-" + time.Now().Format("150405.000000")
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
		tomorrow := time.Now().UTC().AddDate(0, 0, 1)
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
		owner := "statement-owner-" + time.Now().Format("150405.000000")
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
		// that "the previous month = the month today falls in".
		triggerTime := time.Now().UTC().AddDate(0, 1, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))

		sentEmail := waitForSentEmail(t, accountID, "CardUsageStatement")
		require.Equal(t, owner+"@example.com", sentEmail.Recipient)
		require.Contains(t, sentEmail.Subject, cardID)

		period := time.Now().UTC().Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})

	t.Run("re_enqueueing_in_the_same_period_does_not_duplicate_the_statement_send", func(t *testing.T) {
		owner := "statement-idem-owner-" + time.Now().Format("150405.000000")
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
		// twice.
		triggerTime := time.Now().UTC().AddDate(0, 2, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		waitForSentEmail(t, accountID, "CardUsageStatement")

		// The task_outbox.dedup_id UNIQUE constraint means the second
		// enqueue is never even written. Even if it were,
		// Card.MarkStatementSent is idempotent for the same period (Level 1).
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		time.Sleep(5 * time.Second)

		require.Equal(t, 1, countSentEmails(t, accountID, "CardUsageStatement"))
		period := time.Now().UTC().AddDate(0, 1, 0).Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})
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
