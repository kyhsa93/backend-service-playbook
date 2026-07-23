package test

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestSecurityHeaders confirms every response — even an unauthenticated,
// non-rate-limited health check — carries the standard defensive headers
// (security_headers_middleware.go).
func TestSecurityHeaders(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, testServer.URL+"/health/live", nil)
	require.NoError(t, err)

	resp, err := testServer.Client().Do(req)
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()

	require.Equal(t, "nosniff", resp.Header.Get("X-Content-Type-Options"))
	require.Equal(t, "DENY", resp.Header.Get("X-Frame-Options"))
	require.Equal(t, "strict-origin-when-cross-origin", resp.Header.Get("Referrer-Policy"))
	// httptest.NewServer serves plain HTTP with no X-Forwarded-Proto set, so
	// Strict-Transport-Security must be absent here — see
	// security_headers_middleware.go's TLS/trusted-proxy-header gate.
	require.Empty(t, resp.Header.Get("Strict-Transport-Security"))
}

// TestSecurityHeaders_HSTSOverTrustedProxyHeader confirms
// Strict-Transport-Security *is* set once the request carries the
// X-Forwarded-Proto: https header a TLS-terminating reverse proxy would add.
func TestSecurityHeadersHSTSOverTrustedProxyHeader(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, testServer.URL+"/health/live", nil)
	require.NoError(t, err)
	req.Header.Set("X-Forwarded-Proto", "https")

	resp, err := testServer.Client().Do(req)
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()

	require.Equal(t, "max-age=63072000; includeSubDomains", resp.Header.Get("Strict-Transport-Security"))
}

// TestMetricsEndpoint confirms GET /metrics serves real Prometheus-format
// output, including the http_requests_total/http_request_duration_seconds
// series metrics_middleware.go records for every request (the request this
// test itself just made to /health/live above will already have contributed
// a sample by the time /metrics is scraped, since tests share one process).
func TestMetricsEndpoint(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, testServer.URL+"/health/live", nil)
	require.NoError(t, err)
	warmup, err := testServer.Client().Do(req)
	require.NoError(t, err)
	_ = warmup.Body.Close()

	resp, err := testServer.Client().Get(testServer.URL + "/metrics")
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()
	require.Equal(t, http.StatusOK, resp.StatusCode)

	body := decodeText(t, resp)
	require.Contains(t, body, "http_requests_total")
	require.Contains(t, body, "http_request_duration_seconds")
	// r.Pattern (net/http's Go 1.22+ pattern routing) includes the method in
	// the pattern text itself for a route registered via
	// mux.HandleFunc("GET /health/live", ...), so the "path" label's value is
	// the full "GET /health/live", not just the URL path.
	require.Contains(t, body, `path="GET /health/live"`)
}

// TestTraceparentSurvivesOutboxRoundTrip confirms observability.md's "an HTTP
// request and its async event processing show up as one trace": a request
// carrying a W3C traceparent header should produce an outbox row whose
// trace_parent column embeds the same trace id — otelhttp.NewHandler
// (router.go) extracts the incoming header into the span it starts, and
// outbox.Writer.SaveAll (writer.go) reads that same span back out of ctx
// when it inserts the AccountCreated row.
func TestTraceparentSurvivesOutboxRoundTrip(t *testing.T) {
	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	traceParent := "00-" + traceID + "-00f067aa0ba902b7-01"

	req, err := http.NewRequest(http.MethodPost, testServer.URL+"/accounts", strings.NewReader(
		`{"email":"traceparent-e2e@example.com","currency":"KRW"}`))
	require.NoError(t, err)
	token, err := testJWTService.Sign("traceparent-e2e-owner")
	require.NoError(t, err)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("traceparent", traceParent)

	resp, err := testServer.Client().Do(req)
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	account := decodeBody(t, resp)
	accountID, _ := account["accountId"].(string)
	require.NotEmpty(t, accountID)

	// The AccountCreated row is written synchronously inside
	// Repository.Save's transaction (domain-events.md), so it's already
	// present by the time the HTTP response returns — no polling needed for
	// the Writer side. (Poller/Consumer draining it to SQS/a handler happens
	// asynchronously afterward, but this test only needs the row itself.)
	// account.AccountCreated (internal/domain/account/events.go) has no JSON
	// tags, so json.Marshal (outbox.Writer.SaveAll) serializes it using the
	// Go field name as-is — "AccountID", not the "accountId" the HTTP DTO
	// layer uses.
	var storedTraceParent string
	err = testDB.QueryRowContext(context.Background(),
		`SELECT trace_parent FROM outbox WHERE event_type = 'AccountCreated' AND payload->>'AccountID' = $1`,
		accountID,
	).Scan(&storedTraceParent)
	require.NoError(t, err)
	require.Contains(t, storedTraceParent, traceID)
}

func decodeText(t *testing.T, resp *http.Response) string {
	t.Helper()
	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	return string(body)
}
