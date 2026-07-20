package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

type CreatePaymentCommand struct {
	CardID      string
	Amount      int64
	RequesterID string
}

// CreatePaymentHandler는 카드로 결제한다 — Card BC(카드 활성 여부)와 Account BC(계좌 활성
// 여부·잔액 충분 여부)를 동기 Adapter(ACL)로 조회해 판단을 마친 뒤 Payment를 생성하고
// 즉시 완료 처리한다. 실제 계좌 차감은 여기서 하지 않는다 — PaymentCompleted Domain
// Event가 payment.completed.v1 Integration Event로 변환되어 Account BC가 비동기로
// 수행한다(cross-domain.md의 "동기=조회, 비동기=상태변경" 원칙).
type CreatePaymentHandler struct {
	repo     payment.Repository
	cards    PaymentCardAdapter
	accounts PaymentAccountAdapter
}

func NewCreatePaymentHandler(
	repo payment.Repository,
	cards PaymentCardAdapter,
	accounts PaymentAccountAdapter,
) *CreatePaymentHandler {
	return &CreatePaymentHandler{repo: repo, cards: cards, accounts: accounts}
}

func (h *CreatePaymentHandler) Handle(ctx context.Context, cmd CreatePaymentCommand) (*payment.Payment, error) {
	// 동기 Adapter(ACL)로 카드가 존재·활성 상태인지 확인 — 응답(결제 가부)에 필요하므로 동기 호출.
	card, err := h.cards.FindCard(ctx, cmd.CardID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if card == nil {
		return nil, payment.ErrLinkedCardNotFound
	}
	if !card.Active {
		return nil, payment.ErrRequiresActiveCard
	}

	// 동기 Adapter(ACL)로 연결 계좌가 활성이고 잔액이 충분한지 확인(읽기 전용 판단).
	// 실제 차감은 여기서 하지 않는다.
	acc, err := h.accounts.FindAccount(ctx, card.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if acc == nil {
		return nil, payment.ErrLinkedAccountNotFound
	}
	if !acc.Active {
		return nil, payment.ErrRequiresActiveAccount
	}
	if acc.Balance < cmd.Amount {
		return nil, payment.ErrInsufficientBalance
	}

	p := payment.New(cmd.CardID, card.AccountID, cmd.RequesterID, cmd.Amount)
	if err := p.Complete(); err != nil {
		return nil, err
	}

	// 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기 실행되는
	// outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지, domain-events.md).
	if err := h.repo.SavePayment(ctx, p); err != nil {
		return nil, err
	}
	return p, nil
}
