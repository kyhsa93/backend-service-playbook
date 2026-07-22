package http

// Just a plain DTO, no function with the net/http handler signature — a Skip case.
type CreateAccountRequest struct {
	Email string `json:"email"`
}
