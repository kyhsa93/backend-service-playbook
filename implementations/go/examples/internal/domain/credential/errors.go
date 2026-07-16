package credential

import "errors"

var (
	ErrNotFound = errors.New("credential not found")

	// ErrInvalidCredentials는 아이디 미존재와 비밀번호 불일치 두 경우 모두에 쓰는
	// 단일 에러다. 두 경우를 구분해서 응답하면 공격자가 존재하는 아이디를
	// 추측할 수 있다(user enumeration) — sign-in-handler.go가 이 에러 하나로
	// 두 실패 경로를 합류시킨다.
	ErrInvalidCredentials = errors.New("invalid credentials")

	ErrUserIDAlreadyExists = errors.New("user id already exists")
)
