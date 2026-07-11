package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

// QueryRepository는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다.
// Query Handler는 쓰기 메서드(Save)에 접근할 수 없도록 이 인터페이스에만 의존해야 한다.
// Go의 interface는 구조적 타이핑이므로, Repository를 만족하는 구현체는 별도 선언 없이
// QueryRepository도 함께 만족한다 — 구현체를 두 벌 둘 필요가 없다.
type QueryRepository interface {
	FindByID(ctx context.Context, accountID, ownerID string) (*Account, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}

// Repository는 QueryRepository의 읽기 메서드에 쓰기 메서드(Save)를 더한 Command 전용 인터페이스다.
type Repository interface {
	QueryRepository
	Save(ctx context.Context, account *Account) error
}
