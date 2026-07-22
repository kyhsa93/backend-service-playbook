package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID returns a 32-character hex string with hyphens stripped from a UUID v4.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
