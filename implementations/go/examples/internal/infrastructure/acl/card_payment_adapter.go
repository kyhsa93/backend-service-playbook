// CardPaymentAdapter는 Card BC가 다른 Bounded Context(Payment)를 동기 호출할 때 쓰는
// Anticorruption Layer 구현체다. 인터페이스(command.PaymentQueryAdapter)는 호출하는
// 쪽(Card)의 Application 레이어에 있고, 이 구현체는 호출하는 쪽의 Infrastructure에
// 두어 Payment 도메인을 import한다 — 의존 방향은 "Card Infrastructure → Payment
// 도메인"이며 Card의 Application/Domain은 Payment를 전혀 모른다(account_adapter.go,
// payment_adapters.go와 동일한 패턴을 반대 방향으로 적용).
package acl

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

// paymentSummaryBatchSize — SummarizeCardPayments가 대상 기간의 결제를 페이지 단위로
// 순회할 때 쓰는 페이지 크기.
const paymentSummaryBatchSize = 500

// CardPaymentAdapter는 command.PaymentQueryAdapter를 만족하는 ACL 구현체다. Payment
// BC가 노출하는 읽기 인터페이스(payment.Query)를 호출해, Card가 필요로 하는 최소 요약
// (건수·합계)만 번역해 반환한다.
type CardPaymentAdapter struct {
	payments payment.Query
}

func NewCardPaymentAdapter(payments payment.Query) *CardPaymentAdapter {
	return &CardPaymentAdapter{payments: payments}
}

var _ command.PaymentQueryAdapter = (*CardPaymentAdapter)(nil)

// SummarizeCardPayments는 [from, to) 구간에 속하는 cardID의 COMPLETED 결제만 집계한다
// — PENDING/FAILED/CANCELLED 결제는 실제로 청구되지 않았으므로 "카드 이용내역"에
// 포함하지 않는다.
func (a *CardPaymentAdapter) SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error) {
	var summary command.CardPaymentSummary
	for page := 0; ; page++ {
		payments, total, err := a.payments.FindPayments(ctx, payment.FindQuery{
			CardID:      cardID,
			Status:      []payment.Status{payment.StatusCompleted},
			CreatedFrom: from,
			CreatedTo:   to,
			Take:        paymentSummaryBatchSize,
			Page:        page,
		})
		if err != nil {
			return command.CardPaymentSummary{}, fmt.Errorf("card payment adapter summarize: %w", err)
		}
		for _, p := range payments {
			summary.Count++
			summary.TotalAmount += p.Amount
		}
		if len(payments) == 0 || (page+1)*paymentSummaryBatchSize >= total {
			break
		}
	}
	return summary, nil
}
