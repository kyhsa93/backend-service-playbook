package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID returns a 32-character hex string, a UUID v4 with hyphens removed.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
