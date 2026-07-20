package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/credential"
)

type CredentialRepository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증 — account_repository.go/card_repository.go와 동일한 관용구.
var _ credential.Repository = (*CredentialRepository)(nil)

func NewCredentialRepository(db *sql.DB) *CredentialRepository {
	return &CredentialRepository{db: db}
}

// FindCredentials는 UserID 필터로 0건 또는 1건을 반환한다(UserID UNIQUE 제약). 없으면
// 에러 없이 빈 슬라이스를 반환한다 — "존재하지 않음"을 ErrNotFound로 승격하는 판단은
// credential.FindOne 헬퍼의 책임이다(account/card_repository.go의 FindAccounts/FindCards와
// 동일한 관용구).
func (r *CredentialRepository) FindCredentials(ctx context.Context, q credential.FindQuery) ([]*credential.Credential, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT id, user_id, password_hash, created_at FROM credentials WHERE user_id = $1`,
		q.UserID,
	)
	var id, userIDCol, passwordHash string
	var createdAt time.Time
	if err := row.Scan(&id, &userIDCol, &passwordHash, &createdAt); err != nil {
		if err == sql.ErrNoRows {
			return []*credential.Credential{}, nil
		}
		return nil, fmt.Errorf("find credentials: %w", err)
	}
	return []*credential.Credential{credential.Reconstitute(id, userIDCol, passwordHash, createdAt)}, nil
}

// SaveCredential은 신규 Credential만 만든다(비밀번호 변경 유스케이스가 아직 없어 upsert가
// 필요 없다) — user_id에 UNIQUE 인덱스가 있어 SignUpHandler의 사전 중복 확인을
// 통과한 뒤에도 동시 가입 레이스가 있으면 DB 제약이 최종 방어선 역할을 한다.
func (r *CredentialRepository) SaveCredential(ctx context.Context, c *credential.Credential) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO credentials (id, user_id, password_hash, created_at) VALUES ($1, $2, $3, $4)`,
		c.CredentialID, c.UserID, c.PasswordHash, c.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save credential: %w", err)
	}
	return nil
}
