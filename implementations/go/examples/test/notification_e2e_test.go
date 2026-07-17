package test

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

// sesDestination은 LocalStack의 SES 디버그 엔드포인트(/_aws/ses) 응답에서
// 우리가 검증에 필요한 필드만 추려낸 것이다.
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

// fetchSesMessages는 LocalStack 전용 디버그 엔드포인트를 호출해 지금까지 SES
// 에뮬레이터가 "발송"한 모든 이메일 목록을 가져온다. 실제 AWS API에는 없는
// LocalStack만의 기능이다.
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

func TestNotification(t *testing.T) {
	t.Run("계좌_생성시_이메일이_실제로_발송되고_DB에_기록된다", func(t *testing.T) {
		email := "notify-created@example.com"
		account := createAccountWithEmail(t, "notify-owner-created", email, "KRW")
		accountID := account["accountId"].(string)

		sentEmail, found := findSentEmail(t, accountID, "AccountCreated")
		require.True(t, found, "sent_emails에 AccountCreated 기록이 있어야 한다")
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
		require.NotNil(t, matched, "LocalStack SES가 실제로 수신한 메시지와 DB 기록의 MessageId가 일치해야 한다")
		require.Contains(t, matched.Destination.ToAddresses, email)
	})

	t.Run("입금시_이메일이_실제로_발송되고_DB에_기록된다", func(t *testing.T) {
		email := "notify-deposit@example.com"
		account := createAccountWithEmail(t, "notify-owner-deposit", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", "notify-owner-deposit",
			map[string]int{"amount": 5000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		sentEmail, found := findSentEmail(t, accountID, "MoneyDeposited")
		require.True(t, found, "sent_emails에 MoneyDeposited 기록이 있어야 한다")
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

	t.Run("계좌_종료시_이메일이_실제로_발송되고_DB에_기록된다", func(t *testing.T) {
		email := "notify-closed@example.com"
		account := createAccountWithEmail(t, "notify-owner-closed", email, "KRW")
		accountID := account["accountId"].(string)

		resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/close", "notify-owner-closed", nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		sentEmail, found := findSentEmail(t, accountID, "AccountClosed")
		require.True(t, found, "sent_emails에 AccountClosed 기록이 있어야 한다")
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
