package http

type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

type CreateAccountRequest struct {
	Email string `json:"email"`
}
