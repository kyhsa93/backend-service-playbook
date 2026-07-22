package credential

import "errors"

var (
	ErrNotFound = errors.New("credential not found")

	// ErrInvalidCredentials is a single error used for both a nonexistent ID
	// and a password mismatch. Responding differently for the two cases
	// would let an attacker guess which IDs exist (user enumeration) —
	// sign-in-handler.go merges both failure paths into this one error.
	ErrInvalidCredentials = errors.New("invalid credentials")

	ErrUserIDAlreadyExists = errors.New("user id already exists")
)
