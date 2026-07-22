package http

// An extra timestamp field pushes this outside the standard 4-field schema — a violation case.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
	Timestamp  string `json:"timestamp"`
}
