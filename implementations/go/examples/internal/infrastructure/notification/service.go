package notification

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ses"
	"github.com/aws/aws-sdk-go-v2/service/ses/types"

	"github.com/example/account-service/internal/common"
	"github.com/example/account-service/internal/domain/account"
)

// SESClient is a minimal interface exposing only the SES operations needed
// to send notifications. The real implementation is *ses.Client, and it
// can be replaced with a mock in tests.
type SESClient interface {
	SendEmail(ctx context.Context, params *ses.SendEmailInput, optFns ...func(*ses.Options)) (*ses.SendEmailOutput, error)
}

// Service sends Account domain events as SES emails and records the send
// history in the DB.
//
// Send failures (SES errors, DB errors, etc.) are returned as an error —
// the caller, the Outbox's event handler
// (internal/application/event/), receives this error, logs it, and leaves
// the event unprocessed in the outbox table so it's retried on the next
// Drain.
type Service struct {
	sesClient SESClient
	db        *sql.DB
}

// NewService creates a notification service from an SES client and a DB connection.
func NewService(client SESClient, db *sql.DB) *Service {
	return &Service{sesClient: client, db: db}
}

type emailContent struct {
	accountID string
	recipient string
	subject   string
	body      string
}

// Notify sends the notification email corresponding to a domain event and
// saves the send history. It returns an error if sending the email or
// saving fails — since this is called after the account command itself has
// already been committed (via the Outbox), this failure has no effect on
// the account state change.
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) error {
	eventType, content, ok := describe(event)
	if !ok {
		return nil
	}

	if err := s.send(ctx, eventType, content); err != nil {
		return fmt.Errorf("notify %s: %w", eventType, err)
	}
	return nil
}

// NotifyCardStatement sends the monthly card usage statement email and
// saves the send history to sent_emails — it reuses the same send() path
// as Notify (account Domain Event → email) (scheduling.md; in keeping with
// the "Task Controller delegates to a Command with no logic of its own"
// principle, this Service itself shares the existing mechanism rather than
// building a new Card-BC-specific notification mechanism). send() has a
// pure (eventType, emailContent) signature with no dependency on
// account.DomainEvent, so it can be reused without the account package.
func (s *Service) NotifyCardStatement(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error {
	content := emailContent{
		accountID: accountID,
		recipient: recipient,
		subject:   fmt.Sprintf("[Card] Usage statement for card %s (%s)", cardID, period),
		body: fmt.Sprintf("Card (%s) usage for period %s: %d transaction(s), total %d",
			cardID, period, paymentCount, totalAmount),
	}
	if err := s.send(ctx, "CardUsageStatement", content); err != nil {
		return fmt.Errorf("notify card statement: %w", err)
	}
	return nil
}

func (s *Service) send(ctx context.Context, eventType string, content emailContent) error {
	output, err := s.sesClient.SendEmail(ctx, &ses.SendEmailInput{
		Source:      aws.String(SenderEmail()),
		Destination: &types.Destination{ToAddresses: []string{content.recipient}},
		Message: &types.Message{
			Subject: &types.Content{Data: aws.String(content.subject), Charset: aws.String("UTF-8")},
			Body: &types.Body{
				Text: &types.Content{Data: aws.String(content.body), Charset: aws.String("UTF-8")},
			},
		},
	})
	if err != nil {
		return fmt.Errorf("send email: %w", err)
	}

	messageID := aws.ToString(output.MessageId)
	accountID := content.accountID
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO sent_emails (id, account_id, event_type, recipient, subject, ses_message_id)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		common.NewID(), accountID, eventType, content.recipient, content.subject, messageID,
	)
	if err != nil {
		return fmt.Errorf("save sent email record: %w", err)
	}

	slog.InfoContext(ctx, "notification email sent",
		"event_type", eventType,
		"recipient", content.recipient,
		"ses_message_id", messageID,
	)
	return nil
}

func describe(event account.DomainEvent) (string, emailContent, bool) {
	switch e := event.(type) {
	case account.AccountCreated:
		return "AccountCreated", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Your account has been opened",
			body:      fmt.Sprintf("Account (%s) has been opened. Currency: %s", e.AccountID, e.Currency),
		}, true
	case account.MoneyDeposited:
		return "MoneyDeposited", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Deposit completed",
			body: fmt.Sprintf("%d %s was deposited. Balance after deposit: %d %s",
				e.Amount.Amount, e.Amount.Currency, e.BalanceAfter.Amount, e.BalanceAfter.Currency),
		}, true
	case account.MoneyWithdrawn:
		return "MoneyWithdrawn", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Withdrawal completed",
			body: fmt.Sprintf("%d %s was withdrawn. Balance after withdrawal: %d %s",
				e.Amount.Amount, e.Amount.Currency, e.BalanceAfter.Amount, e.BalanceAfter.Currency),
		}, true
	case account.InterestPaid:
		return "InterestPaid", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Interest paid",
			body: fmt.Sprintf("Interest of %d %s was paid. Balance after payment: %d %s",
				e.Amount.Amount, e.Amount.Currency, e.BalanceAfter.Amount, e.BalanceAfter.Currency),
		}, true
	case account.AccountSuspended:
		return "AccountSuspended", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Your account has been suspended",
			body:      fmt.Sprintf("Account (%s) has been suspended.", e.AccountID),
		}, true
	case account.AccountReactivated:
		return "AccountReactivated", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Your account has been reactivated",
			body:      fmt.Sprintf("Account (%s) has been reactivated.", e.AccountID),
		}, true
	case account.AccountClosed:
		return "AccountClosed", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] Your account has been closed",
			body:      fmt.Sprintf("Account (%s) has been closed.", e.AccountID),
		}, true
	default:
		return "", emailContent{}, false
	}
}
