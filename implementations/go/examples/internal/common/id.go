package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID는 UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환한다.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
