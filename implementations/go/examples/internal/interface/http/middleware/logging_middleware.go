package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

// statusRecorder wraps http.ResponseWriter to observe the status code a
// handler actually wrote. net/http provides no way to query the status code
// (the standard library interface alone can't tell whether WriteHeader was
// called, or with what value), so the logging middleware wraps it itself to
// record it.
type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (rec *statusRecorder) WriteHeader(status int) {
	rec.status = status
	rec.wroteHeader = true
	rec.ResponseWriter.WriteHeader(status)
}

func (rec *statusRecorder) Write(b []byte) (int, error) {
	if !rec.wroteHeader {
		// net/http implicitly records 200 if Write is called before
		// WriteHeader — reflect the same default in the log.
		rec.status = http.StatusOK
		rec.wroteHeader = true
	}
	return rec.ResponseWriter.Write(b)
}

// RequestLogging is middleware that leaves a structured log of the
// method/path/status code/duration for every request
// (cross-cutting-concerns.md). It doesn't pass the correlation_id field
// directly here — infrastructure/logging.CorrelationHandler, which main.go
// wraps around the default slog logger, automatically adds the value carried
// in ctx to every log record (observability.md). RequestLogging must be
// registered inside CorrelationID (so it runs later) to inherit that ctx.
func RequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		slog.InfoContext(r.Context(), "http request handled",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}
