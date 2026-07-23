package middleware

import "net/http"

// SecurityHeaders sets standard defensive HTTP response headers on every
// request, unconditionally except for Strict-Transport-Security (see below).
// There's no per-route opt-out — unlike RateLimit/RequireAuth, these headers
// are cheap and safe for every response, including health checks and the
// Swagger UI, so this middleware isn't split out per route group in
// router.go.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Stops the browser from MIME-sniffing a response away from the
		// declared Content-Type (e.g. treating a JSON error body as
		// executable script).
		w.Header().Set("X-Content-Type-Options", "nosniff")
		// This API is never meant to be framed, so refuse all framing
		// outright rather than allow-listing an origin.
		w.Header().Set("X-Frame-Options", "DENY")
		// Sends the full referrer only on same-origin requests, and just the
		// origin (no path/query) cross-origin/downgrade — avoids leaking
		// request paths (which can carry ids/tokens) to third parties.
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")

		// Strict-Transport-Security only makes sense once the request has
		// actually reached this handler over TLS — sending it on a plain-HTTP
		// response would be a lie the browser can't act on anyway. r.TLS is
		// non-nil when Go's own net/http server terminates TLS directly;
		// X-Forwarded-Proto is the header a TLS-terminating reverse
		// proxy/load balancer (this app's assumed prod deployment shape, see
		// container.md/graceful-shutdown.md) sets on the plain-HTTP request
		// it forwards downstream.
		if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
			w.Header().Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
		}

		next.ServeHTTP(w, r)
	})
}
