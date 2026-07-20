package http

// statusCode 태그가 없는 평범한 DTO뿐 — 에러 응답 struct 후보 없음(Skip 케이스).
type CreateAccountRequest struct {
	Email string `json:"email"`
}
