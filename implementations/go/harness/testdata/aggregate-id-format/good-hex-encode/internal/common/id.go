package common

import (
	"crypto/rand"
	"encoding/hex"
)

// NewID는 crypto/rand로 뽑은 16바이트를 32자리 hex로 인코딩한다 — uuid 패키지 없이도
// 하이픈이 생길 수 없는 대안 구현.
func NewID() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
