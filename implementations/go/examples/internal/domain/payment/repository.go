package payment

import "context"

type FindQuery struct {
	Page      int
	Take      int
	PaymentID string
	OwnerID   string
	CardID    string
	AccountID string
	Status    []Status
}

// Query는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다(account.Query,
// card.Query와 동일한 CQRS 분리 관용구 — cqrs-pattern.md).
type Query interface {
	FindPayments(ctx context.Context, q FindQuery) ([]*Payment, int, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(SavePayment)를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	SavePayment(ctx context.Context, p *Payment) error
}

// FindOne은 단건 조회 호출부의 반복되는 패턴(FindPayments를 Take: 1로 호출한 뒤 첫 번째
// 결과를 꺼내고, 없으면 ErrNotFound)을 감싼 헬퍼다(account.FindOne과 동일한 관용구).
func FindOne(ctx context.Context, q Query, paymentID, ownerID string) (*Payment, error) {
	payments, _, err := q.FindPayments(ctx, FindQuery{PaymentID: paymentID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(payments) == 0 {
		return nil, ErrNotFound
	}
	return payments[0], nil
}

// RefundFindQuery — Refund는 ownerId를 갖지 않는다(PaymentID로만 원 결제를 참조한다).
// 소유권 검증은 호출부가 먼저 Payment를 조회해 확인한다.
type RefundFindQuery struct {
	Page      int
	Take      int
	RefundID  string
	PaymentID string
	Status    []RefundStatus
}

// RefundQuery는 Refund의 읽기 전용 조회 인터페이스다.
type RefundQuery interface {
	FindRefunds(ctx context.Context, q RefundFindQuery) ([]*Refund, int, error)
}

// RefundRepository는 RefundQuery에 쓰기 메서드(SaveRefund)를 더한 Command 전용 인터페이스다.
type RefundRepository interface {
	RefundQuery
	SaveRefund(ctx context.Context, r *Refund) error
}
