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

	// HasTransactionWithReference는 Payment BC의 Integration Event 반응(withdraw-by-
	// payment/deposit-by-payment)이 at-least-once 재수신에도 같은 거래를 두 번 만들지
	// 않도록 확인하는 멱등성 체크다(Level 2 Ledger — docs/architecture/domain-events.md
	// 참고). Card의 상태 기반 멱등성(이미 정지된 카드는 다시 정지해도 무해)과 달리 금액
	// 이동은 반복 적용하면 결과가 달라지므로 별도의 처리 여부 확인이 필요하다.
	//
	// txType도 함께 확인해야 한다 — 결제완료(WITHDRAWAL)와 그 결제취소 보상 크레딧
	// (DEPOSIT)은 같은 paymentId를 referenceID로 공유하는 서로 다른 거래이므로,
	// referenceID만으로 확인하면 보상 크레딧이 "이미 처리됨"으로 잘못 판정되어 스킵된다.
	HasTransactionWithReference(ctx context.Context, referenceID string, txType TransactionType) (bool, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(SaveAccount)를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	SaveAccount(ctx context.Context, account *Account) error
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
