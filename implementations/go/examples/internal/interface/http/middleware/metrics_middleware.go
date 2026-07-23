package middleware

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// httpRequestsTotal/httpRequestDuration are package-level, promauto-registered
// collectors — the idiomatic client_golang shape (every real Go service that
// uses this library defines its metrics as package-level vars registered
// once to prometheus.DefaultRegisterer), matching observability.md's
// directional note ("a Prometheus-based GET /metrics endpoint + scraping").
// router.go serves them via promhttp.Handler() on GET /metrics.
var (
	httpRequestsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "http_requests_total",
		Help: "Total number of HTTP requests processed, labeled by method, path, and status code.",
	}, []string{"method", "path", "status"})

	httpRequestDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "http_request_duration_seconds",
		Help:    "HTTP request duration in seconds, labeled by method, path, and status code.",
		Buckets: prometheus.DefBuckets,
	}, []string{"method", "path", "status"})
)

// Metrics is middleware that records http_requests_total/
// http_request_duration_seconds for every request. It must be registered
// directly around the outermost *http.ServeMux in router.go (mirroring where
// RequestLogging sits) rather than further out — net/http's ServeMux (Go
// 1.22+ pattern routing) populates r.Pattern by mutating the *http.Request it
// was handed, and RequireAuth (auth_middleware.go) calls r.WithContext,
// which — being a *copy* — would otherwise hide that mutation from anything
// wrapping outside of it. Registered at the same layer as RequestLogging,
// Metrics sees the same r pointer net/http itself mutates, so the "path"
// label reflects the matched route pattern (e.g. "POST /accounts/{id}/deposit")
// rather than the raw, unbounded-cardinality URL path.
func Metrics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)

		path := r.Pattern
		if path == "" {
			// No pattern matched at all (e.g. a 404) — fall back to the raw
			// path rather than dropping the request from metrics entirely.
			path = r.URL.Path
		}
		status := strconv.Itoa(rec.status)
		httpRequestsTotal.WithLabelValues(r.Method, path, status).Inc()
		httpRequestDuration.WithLabelValues(r.Method, path, status).Observe(time.Since(start).Seconds())
	})
}
