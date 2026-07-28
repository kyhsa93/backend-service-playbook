package test

import (
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// TestWithdrawalAnomalyAlert exercises DetectWithdrawalAnomalyEventHandler
// end to end: Domain Event (MoneyWithdrawn) → Outbox → SQS → Consumer →
// DetectWithdrawalAnomalyEventHandler → account.IsWithdrawalAnomalous →
// notification.Service.NotifyWithdrawalAnomaly → a real sent_emails row +
// an actual LocalStack SES send, the same pattern as TestNotification but
// for the third MoneyWithdrawn subscriber (alongside
// MoneyWithdrawnEventHandler and CategorizeTransactionEventHandler,
// confirming outbox.Consumer's 1:N dispatch holds with three concurrent
// handlers on one event type).
func TestWithdrawalAnomalyAlert(t *testing.T) {
	t.Run("a_withdrawal_far_outside_the_accounts_normal_range_then_an_alert_email_is_sent", func(t *testing.T) {
		email := "notify-anomaly@example.com"
		account := createAccountWithEmail(t, "notify-owner-anomaly", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", "notify-owner-anomaly",
			map[string]int{"amount": 10_000_000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		// Builds a normal history of small, similar withdrawals —
		// account.IsWithdrawalAnomalous needs at least 5 to compute a
		// meaningful baseline.
		for _, amount := range []int{10000, 12000, 9000, 11000, 10500} {
			resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/withdraw", "notify-owner-anomaly",
				map[string]int{"amount": amount})
			require.Equal(t, http.StatusCreated, resp.StatusCode)
		}
		// Far beyond that history's spread — a genuine statistical outlier.
		// Carries a merchantName too, so this single MoneyWithdrawn event
		// exercises all three registered subscribers at once.
		resp = doRequest(t, http.MethodPost, "/accounts/"+accountID+"/withdraw", "notify-owner-anomaly",
			map[string]any{"amount": 5_000_000, "merchantName": "Unusual Store"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		sentEmail := waitForSentEmail(t, accountID, "WithdrawalAnomalyDetected")
		require.Equal(t, email, sentEmail.Recipient)
		require.NotEmpty(t, sentEmail.SesMessageID)

		messages := fetchSesMessages(t)
		var matched *sesMessage
		for i := range messages {
			if messages[i].ID == sentEmail.SesMessageID {
				matched = &messages[i]
				break
			}
		}
		require.NotNil(t, matched, "the MessageId actually received by LocalStack SES must match the DB record")
		require.Contains(t, matched.Destination.ToAddresses, email)

		// Confirms the other two MoneyWithdrawn subscribers also ran for
		// this exact event — outbox.Consumer's 1:N dispatch (see
		// internal/infrastructure/outbox/consumer.go) now fans out to three
		// concurrent handlers, not just two: MoneyWithdrawnEventHandler (the
		// plain withdrawal-completed email) and CategorizeTransactionEventHandler
		// (merchant categorization) alongside DetectWithdrawalAnomalyEventHandler.
		withdrawnEmail := waitForSentEmail(t, accountID, "MoneyWithdrawn")
		require.Equal(t, email, withdrawnEmail.Recipient)

		// waitForCategorizedTransaction (account_e2e_test.go) hardcodes the
		// package-level ownerID const for auth, so it can't be reused here —
		// this account belongs to "notify-owner-anomaly" instead. Same
		// polling idiom, scoped to the right owner.
		var categorizedTx map[string]any
		deadline := time.Now().Add(30 * time.Second)
		for time.Now().Before(deadline) {
			resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/transactions", "notify-owner-anomaly", nil)
			body := decodeBody(t, resp)
			if transactions, ok := body["transactions"].([]any); ok {
				for _, raw := range transactions {
					tx := raw.(map[string]any)
					if tx["type"] == "WITHDRAWAL" && tx["category"] != nil {
						categorizedTx = tx
						break
					}
				}
			}
			if categorizedTx != nil {
				break
			}
			time.Sleep(200 * time.Millisecond)
		}
		require.NotNil(t, categorizedTx, "no categorized transaction found (timed out)")
		require.Equal(t, "Unusual Store", categorizedTx["merchantName"])
	})

	t.Run("withdrawals_that_stay_within_the_accounts_normal_range_then_no_alert_email_is_ever_sent", func(t *testing.T) {
		email := "notify-no-anomaly@example.com"
		account := createAccountWithEmail(t, "notify-owner-no-anomaly", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", "notify-owner-no-anomaly",
			map[string]int{"amount": 10_000_000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		for _, amount := range []int{10000, 12000, 9000, 11000, 10500, 10800} {
			resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/withdraw", "notify-owner-no-anomaly",
				map[string]int{"amount": amount})
			require.Equal(t, http.StatusCreated, resp.StatusCode)
		}

		// No single "the async work finished" signal to await for a negative
		// case — give the pipeline the same window the positive test needs,
		// then assert nothing landed (the same idiom as
		// TestTransactionAutoCategorization's negative case).
		time.Sleep(5 * time.Second)
		_, found := findSentEmail(t, accountID, "WithdrawalAnomalyDetected")
		require.False(t, found, "no WithdrawalAnomalyDetected alert should have been sent")
	})
}
