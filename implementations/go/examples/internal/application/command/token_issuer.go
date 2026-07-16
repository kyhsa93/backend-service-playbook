package command

// TokenIssuer는 인증 성공 후 Access Token을 발급하는 포트다. 실제 구현(JWT)은
// internal/infrastructure/auth에 있다 — *auth.JWTService가 이미 이 시그니처
// (Sign(userID string) (string, error))를 그대로 만족하므로, Go의 구조적 타이핑
// 덕분에 별도 어댑터 타입 없이 곧바로 주입할 수 있다.
type TokenIssuer interface {
	Sign(userID string) (string, error)
}
