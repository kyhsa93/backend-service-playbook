package common

import "github.com/google/uuid"

// NewID는 하이픈이 포함된 원본 UUID v4 문자열을 그대로 반환한다 — 위반 사례.
func NewID() string {
	return uuid.New().String()
}
