package http

// timestamp 필드가 추가되어 표준 4필드 스키마를 벗어난 위반 사례.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
	Timestamp  string `json:"timestamp"`
}
