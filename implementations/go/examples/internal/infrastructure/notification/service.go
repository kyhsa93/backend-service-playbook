package notification

import (
	"context"
	"database/sql"
	"fmt"
	"log"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ses"
	"github.com/aws/aws-sdk-go-v2/service/ses/types"
	"github.com/google/uuid"

	"github.com/example/account-service/internal/domain/account"
)

// SESClient는 알림 발송에 필요한 SES 동작만 노출하는 최소 인터페이스다.
// 실제 구현은 *ses.Client가 맡고, 테스트에서는 목으로 대체할 수 있다.
type SESClient interface {
	SendEmail(ctx context.Context, params *ses.SendEmailInput, optFns ...func(*ses.Options)) (*ses.SendEmailOutput, error)
}

// Service는 Account 도메인 이벤트를 SES 이메일로 발송하고, 발송 내역을 DB에 남긴다.
//
// 발송 실패(SES 오류, DB 오류 등)는 커맨드 처리 자체를 실패시키지 않도록 이 서비스
// 내부에서 로깅 후 삼켜진다. 호출부(커맨드 핸들러)는 반환값을 신경 쓸 필요가 없다.
type Service struct {
	sesClient SESClient
	db        *sql.DB
}

// NewService는 SES 클라이언트와 DB 커넥션으로 알림 서비스를 만든다.
func NewService(client SESClient, db *sql.DB) *Service {
	return &Service{sesClient: client, db: db}
}

type emailContent struct {
	accountID string
	recipient string
	subject   string
	body      string
}

// Notify는 도메인 이벤트에 대응하는 알림 이메일을 발송하고 발송 내역을 저장한다.
// 이메일 발송 또는 저장에 실패해도 에러를 반환하지 않고 로그만 남긴다 — 알림은
// 부가 기능이므로 계좌 커맨드의 성공 여부에 영향을 주면 안 된다.
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) {
	eventType, content, ok := describe(event)
	if !ok {
		return
	}

	if err := s.send(ctx, eventType, content); err != nil {
		log.Printf("notification: failed to send email for event=%s recipient=%s: %v", eventType, content.recipient, err)
	}
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
		uuid.NewString(), accountID, eventType, content.recipient, content.subject, messageID,
	)
	if err != nil {
		return fmt.Errorf("save sent email record: %w", err)
	}

	log.Printf("notification: email sent event=%s recipient=%s ses_message_id=%s", eventType, content.recipient, messageID)
	return nil
}

func describe(event account.DomainEvent) (string, emailContent, bool) {
	switch e := event.(type) {
	case account.AccountCreated:
		return "AccountCreated", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 계좌가 개설되었습니다",
			body:      fmt.Sprintf("계좌(%s)가 개설되었습니다. 통화: %s", e.AccountID, e.Currency),
		}, true
	case account.MoneyDeposited:
		return "MoneyDeposited", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 입금이 완료되었습니다",
			body: fmt.Sprintf("%d %s이 입금되었습니다. 입금 후 잔액: %d %s",
				e.Amount.Amount, e.Amount.Currency, e.BalanceAfter.Amount, e.BalanceAfter.Currency),
		}, true
	case account.MoneyWithdrawn:
		return "MoneyWithdrawn", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 출금이 완료되었습니다",
			body: fmt.Sprintf("%d %s이 출금되었습니다. 출금 후 잔액: %d %s",
				e.Amount.Amount, e.Amount.Currency, e.BalanceAfter.Amount, e.BalanceAfter.Currency),
		}, true
	case account.AccountSuspended:
		return "AccountSuspended", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 계좌가 정지되었습니다",
			body:      fmt.Sprintf("계좌(%s)가 정지되었습니다.", e.AccountID),
		}, true
	case account.AccountReactivated:
		return "AccountReactivated", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 계좌가 재개되었습니다",
			body:      fmt.Sprintf("계좌(%s)가 재개되었습니다.", e.AccountID),
		}, true
	case account.AccountClosed:
		return "AccountClosed", emailContent{
			accountID: e.AccountID,
			recipient: e.Email,
			subject:   "[Account] 계좌가 종료되었습니다",
			body:      fmt.Sprintf("계좌(%s)가 종료되었습니다.", e.AccountID),
		}, true
	default:
		return "", emailContent{}, false
	}
}
