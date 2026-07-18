package payment

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Payment는 결제를 표현하는 Aggregate Root다. CardID/AccountID로 어느 카드·어느 계좌가
// 관련됐는지 참조만 하고(BC 경계를 넘는 FK 없음), 카드가 활성인지·계좌 잔액이 충분한지에
// 대한 실제 판단은 Application 레이어가 CardAdapter/AccountAdapter(ACL)로 동기 조회해
// 이 Aggregate를 만들기 전에 끝낸다 — Payment 자신은 그 판단 근거를 모른다.
type Payment struct {
	PaymentID string
	CardID    string
	AccountID string
	OwnerID   string
	Amount    int64
	Status    Status
	CreatedAt time.Time
	events    []DomainEvent
}

// New는 결제를 PENDING 상태로 만드는 순수 생성 팩토리다. 카드 활성 여부·계좌 잔액 충분
// 여부는 이미 Application 레이어의 동기 Adapter 호출로 판정이 끝난 뒤 호출된다 —
// 이벤트는 발생시키지 않는다(Complete()가 완료 시점에 PaymentCompleted를 발생시킨다).
func New(cardID, accountID, ownerID string, amount int64) *Payment {
	return &Payment{
		PaymentID: common.NewID(),
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Amount:    amount,
		Status:    StatusPending,
		CreatedAt: time.Now(),
	}
}

// Reconstitute는 저장소에서 읽은 행을 도메인 객체로 되살린다(불변식 검사 없이 그대로 복원).
func Reconstitute(paymentID, cardID, accountID, ownerID string, amount int64, status Status, createdAt time.Time) *Payment {
	return &Payment{
		PaymentID: paymentID,
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Amount:    amount,
		Status:    status,
		CreatedAt: createdAt,
	}
}

// Complete는 결제를 완료 처리한다. 현재 CreatePaymentHandler는 통과 여부를 생성 이전에
// 동기 Adapter로 판정하므로 New() 직후 곧바로 호출되고, PENDING으로 만들어진 뒤 실패하는
// 경로는 없다. 다만 향후 결제 게이트웨이 콜백처럼 비동기로 결과가 도착하는 시나리오를
// 대비해 상태 전이 자체는 Aggregate가 갖고 있는다(Domain 단위 테스트로 검증).
func (p *Payment) Complete() error {
	if p.Status != StatusPending {
		return ErrCompleteRequiresPendingPayment
	}
	p.Status = StatusCompleted
	p.events = append(p.events, PaymentCompleted{
		PaymentID:   p.PaymentID,
		CardID:      p.CardID,
		AccountID:   p.AccountID,
		OwnerID:     p.OwnerID,
		Amount:      p.Amount,
		CompletedAt: time.Now(),
	})
	return nil
}

// Fail은 결제를 실패 처리한다. 현재 어떤 Command도 이 메서드를 호출하지 않는다(비동기
// 결제 게이트웨이 콜백 흐름이 아직 없음) — 상태 전이의 완결성을 위해 남겨두고 Domain 단위
// 테스트로만 검증한다(Complete()와 대칭인 가드).
func (p *Payment) Fail(reason string) error {
	if p.Status != StatusPending {
		return ErrFailRequiresPendingPayment
	}
	p.Status = StatusFailed
	return nil
}

// Cancel은 결제취소다. 결제취소는 이미 확정된(COMPLETED) 결제를 되돌리는 것이므로
// COMPLETED에서만 가능하다. Account BC가 PaymentCancelled를 구독해 보상 크레딧을 실행한다.
func (p *Payment) Cancel(reason string) error {
	if p.Status != StatusCompleted {
		return ErrCancelRequiresCompletedPayment
	}
	p.Status = StatusCancelled
	p.events = append(p.events, PaymentCancelled{
		PaymentID:   p.PaymentID,
		AccountID:   p.AccountID,
		OwnerID:     p.OwnerID,
		Amount:      p.Amount,
		Reason:      reason,
		CancelledAt: time.Now(),
	})
	return nil
}

func (p *Payment) DomainEvents() []DomainEvent { return p.events }
func (p *Payment) ClearEvents()                { p.events = nil }
