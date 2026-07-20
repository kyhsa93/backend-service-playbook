package credential

import "context"

// FindQuery는 UserID 단일 필터만 갖는다 — UserID는 유일해야 하므로(가입 시 중복 확인)
// account.FindQuery처럼 Page/Take를 둘 필요가 없다. 여러 건을 조합해 조회할 유스케이스가
// 없으므로 페이지네이션 자체가 불필요하다.
type FindQuery struct {
	UserID string
}

// Query는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다(account.Query와
// 동일한 CQRS 분리 관용구 — cqrs-pattern.md).
//
// 조회는 root의 find<Noun>s 컨벤션에 맞춰 FindCredentials 단일 메서드로 통일한다 — 단건
// 조회 전용 메서드는 두지 않는다. 호출부는 FindOne(이 패키지가 제공하는 헬퍼)으로
// FindCredentials를 호출하고 첫 결과를 꺼낸다(account.FindOne과 동일한 관용구).
type Query interface {
	FindCredentials(ctx context.Context, q FindQuery) ([]*Credential, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(SaveCredential)를 더한 Command 전용
// 인터페이스다.
type Repository interface {
	Query
	SaveCredential(ctx context.Context, credential *Credential) error
}

// FindOne은 단건 조회 호출부의 반복되는 패턴(FindCredentials를 호출한 뒤 첫 번째 결과를
// 꺼내고, 없으면 ErrNotFound)을 감싼 헬퍼다(account.FindOne과 동일한 관용구).
func FindOne(ctx context.Context, q Query, userID string) (*Credential, error) {
	credentials, err := q.FindCredentials(ctx, FindQuery{UserID: userID})
	if err != nil {
		return nil, err
	}
	if len(credentials) == 0 {
		return nil, ErrNotFound
	}
	return credentials[0], nil
}
