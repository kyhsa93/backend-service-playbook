package credential

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Credential is the Aggregate Root representing a login credential (an ID
// plus a hashed password). UserID is an external reference that shares the
// same value space as Account.OwnerID, but Credential does not reference
// Account directly — Authentication (Auth) and Account are separate
// Bounded Contexts, and this repository has no explicit coordination logic
// such as "also create an Account on sign-up" (currently a user can open an
// Account freely under any owner_id).
//
// The plaintext password is never stored anywhere in Credential — it holds
// only PasswordHash.
type Credential struct {
	CredentialID string
	UserID       string
	PasswordHash string
	CreatedAt    time.Time
}

// New creates a new Credential from an already-hashed password. Hashing
// itself is the responsibility of a Technical Service (PasswordHasher), so
// Credential knows nothing about the hash algorithm.
func New(userID, passwordHash string) *Credential {
	return &Credential{
		CredentialID: common.NewID(),
		UserID:       userID,
		PasswordHash: passwordHash,
		CreatedAt:    time.Now(),
	}
}

// Reconstitute restores a row read from storage into a domain object (restored as-is, without invariant checks).
func Reconstitute(credentialID, userID, passwordHash string, createdAt time.Time) *Credential {
	return &Credential{
		CredentialID: credentialID,
		UserID:       userID,
		PasswordHash: passwordHash,
		CreatedAt:    createdAt,
	}
}
