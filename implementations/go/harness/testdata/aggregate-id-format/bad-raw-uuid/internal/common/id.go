package common

import "github.com/google/uuid"

// NewID returns the raw UUID v4 string as-is, hyphens included — a violation case.
func NewID() string {
	return uuid.New().String()
}
