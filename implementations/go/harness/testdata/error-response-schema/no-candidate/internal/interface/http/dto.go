package http

// Just a plain DTO without a statusCode tag — no error response struct candidate (Skip case).
type CreateAccountRequest struct {
	Email string `json:"email"`
}
