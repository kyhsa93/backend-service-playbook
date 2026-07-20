package http

import "net/http"

func NewRouter() http.Handler {
	return http.NewServeMux()
}
