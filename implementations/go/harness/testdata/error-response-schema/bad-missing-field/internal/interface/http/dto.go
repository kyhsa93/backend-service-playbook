package http

// error 필드가 없고 errorCode라는 다른 이름을 쓰는 위반 사례.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	ErrorCode  string `json:"errorCode"`
	Message    string `json:"message"`
}
