package common

import (
	"crypto/rand"
	"encoding/hex"
)

// NewID encodes 16 random bytes from crypto/rand as a 32-character hex string —
// an alternative implementation that can't produce hyphens, without using the
// uuid package.
func NewID() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
