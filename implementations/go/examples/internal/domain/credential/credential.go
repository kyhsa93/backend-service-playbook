package credential

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Credential은 로그인 자격증명(아이디+해시된 비밀번호)을 표현하는 Aggregate Root다.
// UserID는 Account.OwnerID와 동일한 값 공간을 쓰는 외부 참조이지만, Credential은
// Account를 직접 참조하지 않는다 — 인증(Auth)과 계좌(Account)는 서로 다른 Bounded
// Context이고 이 저장소에는 아직 명시적 "회원가입 시 Account도 함께 만든다" 같은
// 조율 로직이 없다(현재 Account는 사용자가 어떤 owner_id로든 자유롭게 개설한다).
//
// 평문 비밀번호는 Credential 어디에도 보관하지 않는다 — PasswordHash만 가진다.
type Credential struct {
	CredentialID string
	UserID       string
	PasswordHash string
	CreatedAt    time.Time
}

// New는 이미 해싱된 비밀번호로 새 Credential을 만든다. 해싱 자체는 Technical
// Service(PasswordHasher)의 책임이라 Credential은 해시 알고리즘을 전혀 모른다.
func New(userID, passwordHash string) *Credential {
	return &Credential{
		CredentialID: common.NewID(),
		UserID:       userID,
		PasswordHash: passwordHash,
		CreatedAt:    time.Now(),
	}
}

// Reconstitute는 저장소에서 읽은 행을 도메인 객체로 되살린다(불변식 검사 없이 그대로 복원).
func Reconstitute(credentialID, userID, passwordHash string, createdAt time.Time) *Credential {
	return &Credential{
		CredentialID: credentialID,
		UserID:       userID,
		PasswordHash: passwordHash,
		CreatedAt:    createdAt,
	}
}
