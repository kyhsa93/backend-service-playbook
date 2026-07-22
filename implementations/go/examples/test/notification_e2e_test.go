package test

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// sesDestination extracts just the fields we need for assertions from the
// LocalStack SES debug endpoint (/_aws/ses) response.
type sesDestination struct {
	ToAddresses []string `json:"ToAddresses"`
}

type sesMessage struct {
	ID          string         `json:"Id"`
	Source      string         `json:"Source"`
	Subject     string         `json:"Subject"`
	Destination sesDestination `json:"Destination"`
}

type sesMessagesResponse struct {
	Messages []sesMessage `json:"messages"`
}

// fetchSesMessages calls the LocalStack-only debug endpoint to fetch every
// email the SES emulator has "sent" so far. This is a LocalStack-only
// feature that doesn't exist in the real AWS API.
func fetchSesMessages(t *testing.T) []sesMessage {
	t.Helper()
	resp, err := http.Get(localstackHTTP + "/_aws/ses")
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()
	require.Equal(t, http.StatusOK, resp.StatusCode)

	var body sesMessagesResponse
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&body))
	return body.Messages
}

type sentEmailRow struct {
	ID           string
	AccountID    string
	EventType    string
	Recipient    string
	Subject      string
	SesMessageID string
}

func findSentEmail(t *testing.T, accountID, eventType string) (sentEmailRow, bool) {
	t.Helper()
	row := testDB.QueryRow(
		`SELECT id, account_id, event_type, recipient, subject, ses_message_id
		 FROM sent_emails WHERE account_id = $1 AND event_type = $2`,
		accountID, eventType,
	)
	var r sentEmailRow
	if err := row.Scan(&r.ID, &r.AccountID, &r.EventType, &r.Recipient, &r.Subject, &r.SesMessageID); err != nil {
		return sentEmailRow{}, false
	}
	return r, true
}

// waitForSentEmail polls findSentEmail — since email sending is performed
// asynchronously by an EventHandler via the Outbox → SQS → Consumer path, it
// may not yet be recorded in sent_emails by the time the HTTP response
// returns (the same budget as waitForCardStatus/waitForAccountBalance —
// Poller's 1-second tick + Consumer's 5-second long polling + LocalStack
// latency).
func waitForSentEmail(t *testing.T, accountID, eventType string) sentEmailRow {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if row, found := findSentEmail(t, accountID, eventType); found {
			return row
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("no sent_emails record found for accountId=%s eventType=%s (timed out)", accountID, eventType)
	return sentEmailRow{}
}

func TestNotification(t *testing.T) {
	t.Run("an_email_is_actually_sent_and_recorded_in_the_DB_on_account_creation", func(t *testing.T) {
		email := "notify-created@example.com"
		account := createAccountWithEmail(t, "notify-owner-created", email, "KRW")
		accountID := account["accountId"].(string)

		sentEmail := waitForSentEmail(t, accountID, "AccountCreated")
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
	})

	t.Run("an_email_is_actually_sent_and_recorded_in_the_DB_on_deposit", func(t *testing.T) {
		email := "notify-deposit@example.com"
		account := createAccountWithEmail(t, "notify-owner-deposit", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", "notify-owner-deposit",
			map[string]int{"amount": 5000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		sentEmail := waitForSentEmail(t, accountID, "MoneyDeposited")
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
		require.NotNil(t, matched)
		require.Contains(t, matched.Destination.ToAddresses, email)
	})

	t.Run("an_email_is_actually_sent_and_recorded_in_the_DB_on_account_closure", func(t *testing.T) {
		email := "notify-closed@example.com"
		account := createAccountWithEmail(t, "notify-owner-closed", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/close", "notify-owner-closed", nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		sentEmail := waitForSentEmail(t, accountID, "AccountClosed")
		require.Equal(t, email, sentEmail.Recipient)

		messages := fetchSesMessages(t)
		var matched *sesMessage
		for i := range messages {
			if messages[i].ID == sentEmail.SesMessageID {
				matched = &messages[i]
				break
			}
		}
		require.NotNil(t, matched)
	})
}
