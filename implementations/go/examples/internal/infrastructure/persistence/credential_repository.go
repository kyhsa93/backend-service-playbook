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

func (r *CredentialRepository) FindByUserID(ctx context.Context, userID string) (*credential.Credential, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT id, user_id, password_hash, created_at FROM credentials WHERE user_id = $1`,
		userID,
	)
	var id, userIDCol, passwordHash string
	var createdAt time.Time
	if err := row.Scan(&id, &userIDCol, &passwordHash, &createdAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, credential.ErrNotFound
		}
		return nil, fmt.Errorf("find credential by user id: %w", err)
	}
	return credential.Reconstitute(id, userIDCol, passwordHash, createdAt), nil
}

// Save는 신규 Credential만 만든다(비밀번호 변경 유스케이스가 아직 없어 upsert가
// 필요 없다) — user_id에 UNIQUE 인덱스가 있어 SignUpHandler의 사전 중복 확인을
// 통과한 뒤에도 동시 가입 레이스가 있으면 DB 제약이 최종 방어선 역할을 한다.
func (r *CredentialRepository) Save(ctx context.Context, c *credential.Credential) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO credentials (id, user_id, password_hash, created_at) VALUES ($1, $2, $3, $4)`,
		c.CredentialID, c.UserID, c.PasswordHash, c.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save credential: %w", err)
	}
	return nil
}
