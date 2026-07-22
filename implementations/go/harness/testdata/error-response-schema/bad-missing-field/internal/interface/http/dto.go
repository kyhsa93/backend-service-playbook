package http

// Missing the error field and using a differently named errorCode field instead — a violation case.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	ErrorCode  string `json:"errorCode"`
	Message    string `json:"message"`
}
