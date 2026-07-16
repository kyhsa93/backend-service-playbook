package credential

import "context"

// Repository는 Credential의 조회/저장을 담당한다. UserID는 유일해야 하므로(가입 시
// 중복 확인) account.Repository처럼 목록 조회(FindAll)는 두지 않고 단건 조회
// 메서드만 노출한다 — repository-pattern.md가 밝힌 "Go 관용에서는 단건 조회를
// 별도 메서드로 분리하는 쪽이 자연스럽다"는 원칙을 그대로 따른 것이다.
type Repository interface {
	FindByUserID(ctx context.Context, userID string) (*Credential, error)
	Save(ctx context.Context, credential *Credential) error
}
