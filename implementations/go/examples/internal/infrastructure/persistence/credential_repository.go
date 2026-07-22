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

// Compile-time interface satisfaction check — the same idiom as account_repository.go/card_repository.go.
var _ credential.Repository = (*CredentialRepository)(nil)

func NewCredentialRepository(db *sql.DB) *CredentialRepository {
	return &CredentialRepository{db: db}
}

// FindCredentials returns 0 or 1 records filtered by UserID (UNIQUE
// constraint on UserID). If there is none, it returns an empty slice with
// no error — deciding to promote "doesn't exist" to ErrNotFound is the
// responsibility of the credential.FindOne helper (the same idiom as
// FindAccounts/FindCards in account/card_repository.go).
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

// SaveCredential only creates new Credentials (there's no password-change
// use case yet, so upsert isn't needed) — since user_id has a UNIQUE index,
// even if there's a concurrent sign-up race after SignUpHandler's
// pre-check for duplicates passes, the DB constraint serves as the final
// line of defense.
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
