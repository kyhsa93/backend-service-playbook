package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

// Query는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다.
// Query Handler는 쓰기 메서드(Save)에 접근할 수 없도록 이 인터페이스에만 의존해야 한다.
// Go의 interface는 구조적 타이핑이므로, Repository를 만족하는 구현체는 별도 선언 없이
// Query도 함께 만족한다 — 구현체를 두 벌 둘 필요가 없다.
//
// 조회는 root의 find<Noun>s 컨벤션에 맞춰 FindAccounts 단일 메서드로 통일한다 — 단건
// 조회 전용 메서드는 두지 않는다. 호출부는 FindOne(이 패키지가 제공하는 헬퍼)로
// FindAccounts를 Take: 1로 호출하고 첫 결과를 꺼낸다.
type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(Save)를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	Save(ctx context.Context, account *Account) error
}

// FindOne은 단건 조회 호출부의 반복되는 패턴(FindAccounts를 Take: 1로 호출한 뒤
// 첫 번째 결과를 꺼내고, 없으면 ErrNotFound)을 감싼 헬퍼다. java/kotlin-springboot의
// findAccounts(...).stream().findFirst().orElseThrow(...) 관용구와 동일한 역할을 하되,
// Go에는 Stream이 없으므로 자유 함수로 추출했다 — Repository/Query 인터페이스에는
// 여전히 조회 메서드가 FindAccounts 하나뿐이다.
func FindOne(ctx context.Context, q Query, accountID, ownerID string) (*Account, error) {
	accounts, _, err := q.FindAccounts(ctx, FindQuery{AccountID: accountID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(accounts) == 0 {
		return nil, ErrNotFound
	}
	return accounts[0], nil
}
