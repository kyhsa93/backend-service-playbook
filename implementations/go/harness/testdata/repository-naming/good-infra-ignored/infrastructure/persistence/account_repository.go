package persistence

import "context"

// AccountRepository는 domain.Repository의 구현체다. private/internal 헬퍼 메서드
// 이름은 이 규칙의 대상이 아니다 — Repository/Query 인터페이스는 domain/ 레이어에만
// 있으므로 infrastructure/의 구현체는 스캔 대상에서 제외된다.
type AccountRepository struct{}

func (r *AccountRepository) FindByID(ctx context.Context, id string) (*Account, error) {
	return nil, nil
}

func (r *AccountRepository) Save(ctx context.Context, a *Account) error {
	return nil
}
