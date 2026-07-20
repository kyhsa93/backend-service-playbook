package http

import (
	"net/http"

	"github.com/example/account-service/internal/interface/http/middleware"
)

func NewRouter() http.Handler {
	mux := http.NewServeMux()
	return middleware.RateLimit(100)(mux)
}
