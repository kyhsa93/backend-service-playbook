package credential

import "context"

// Repository는 Credential의 조회/저장을 담당한다. UserID는 유일해야 하므로(가입 시
// 중복 확인) account.Repository의 FindAccounts처럼 필터 기반의 목록 조회는 두지 않고
// 단건 조회 메서드만 노출한다 — Credential은 필터를 조합해 여러 건을 조회할 유스케이스가
// 없으므로 목록 조회 메서드 자체가 불필요하다.
type Repository interface {
	FindByUserID(ctx context.Context, userID string) (*Credential, error)
	Save(ctx context.Context, credential *Credential) error
}
