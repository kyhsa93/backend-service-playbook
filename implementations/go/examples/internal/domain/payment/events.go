package payment

import "time"

// DomainEvent는 Payment/Refund 두 Aggregate가 함께 쓰는 마커 인터페이스다(account.DomainEvent와
// 동일한 관용구). Payment BC는 자신의 Domain Event를 공유 outbox.Writer(현재
// []account.DomainEvent에 고정된 시그니처)로 적재할 수 없으므로, Repository.Save가 같은
// 트랜잭션 안에서 직접 outbox row를 insert한다(infrastructure/persistence/payment_repository.go
// 참고, scripts/create-domain이 생성하는 Repository와 동일한 우회 패턴).
type DomainEvent interface {
	isPaymentDomainEvent()
}

// PaymentCompleted는 결제가 즉시 완료 처리될 때 발생한다(현재 CreatePaymentHandler가 동기
// Adapter로 판정을 마친 뒤 곧바로 Complete()를 호출하므로 PENDING으로 머무는 구간이 없다).
// Account BC가 이 이벤트를 구독해 실제 차감(withdraw)을 비동기로 수행한다 —
// accountId+amount만 실은 얇은 Integration Event(payment.completed.v1)로 변환되어
// application/event/payment_completed_event_handler.go가 Outbox에 적재한다.
type PaymentCompleted struct {
	PaymentID   string
	CardID      string
	AccountID   string
	OwnerID     string
	Amount      int64
	CompletedAt time.Time
}

func (PaymentCompleted) isPaymentDomainEvent() {}

// PaymentCancelled는 완료된 결제가 취소될 때 발생한다. Account BC가 구독해 보상 크레딧
// (deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
type PaymentCancelled struct {
	PaymentID   string
	AccountID   string
	OwnerID     string
	Amount      int64
	Reason      string
	CancelledAt time.Time
}

func (PaymentCancelled) isPaymentDomainEvent() {}

// RefundApproved는 RefundEligibilityService의 판단으로 환불이 승인될 때 발생한다.
// Refund 자신은 accountId/ownerId를 모르므로(paymentId만 참조) Approve() 호출 시
// Application 레이어가 이미 로드해둔 Payment에서 얻은 값을 함께 실어 이 이벤트를 만든다.
type RefundApproved struct {
	RefundID   string
	PaymentID  string
	AccountID  string
	OwnerID    string
	Amount     int64
	ApprovedAt time.Time
}

func (RefundApproved) isPaymentDomainEvent() {}
