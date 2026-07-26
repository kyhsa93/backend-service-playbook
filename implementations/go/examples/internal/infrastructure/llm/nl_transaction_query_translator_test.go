package llm_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/llm"
)

// ollamaChatServer returns an httptest.Server that responds to any request
// exactly as Ollama's /api/chat would, with content as the model's raw
// message content (the caller controls whether that's schema-shaped JSON
// or free-form prose).
func ollamaChatServer(content string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"message": map[string]string{"content": content},
		})
	}))
}

// unreachableServer returns a URL nothing is listening on (the server is
// started then immediately closed), so a request to it fails fast with a
// connection error — used to simulate an Ollama outage without depending
// on any specific unassigned port being refused the same way in every
// environment.
func unreachableServer() string {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	s.Close()
	return s.URL
}

func TestNlTransactionQueryTranslatorImpl_Translate_ValidTypeAndDates_ReturnsThemAsTheFilter(t *testing.T) {
	server := ollamaChatServer(`{"type":"WITHDRAWAL","fromDate":"2026-07-01","toDate":"2026-07-31"}`)
	defer server.Close()
	translator := llm.NewNlTransactionQueryTranslatorImpl(server.URL, "test-model")

	filter := translator.Translate(context.Background(), "How much did I withdraw in July?")

	want := query.TransactionFilter{Type: account.TransactionTypeWithdrawal, FromDate: "2026-07-01", ToDate: "2026-07-31"}
	if filter != want {
		t.Fatalf("Translate() = %+v, want %+v", filter, want)
	}
}

func TestNlTransactionQueryTranslatorImpl_Translate_InvalidType_DropsItRatherThanPassingItThrough(t *testing.T) {
	server := ollamaChatServer(`{"type":"NOT_A_REAL_TYPE","fromDate":"","toDate":""}`)
	defer server.Close()
	translator := llm.NewNlTransactionQueryTranslatorImpl(server.URL, "test-model")

	filter := translator.Translate(context.Background(), "anything")

	if filter.Type != "" {
		t.Fatalf("Type = %q, want empty (dropped)", filter.Type)
	}
}

func TestNlTransactionQueryTranslatorImpl_Translate_MalformedDate_DropsItRatherThanPassingItThrough(t *testing.T) {
	server := ollamaChatServer(`{"type":"ANY","fromDate":"not-a-date","toDate":"2026-13-99"}`)
	defer server.Close()
	translator := llm.NewNlTransactionQueryTranslatorImpl(server.URL, "test-model")

	filter := translator.Translate(context.Background(), "anything")

	if filter.FromDate != "" {
		t.Fatalf("FromDate = %q, want empty (dropped)", filter.FromDate)
	}
	if filter.ToDate != "" {
		t.Fatalf("ToDate = %q, want empty (dropped)", filter.ToDate)
	}
}

func TestNlTransactionQueryTranslatorImpl_Translate_MalformedOllamaOutput_FallsBackToNoFilter(t *testing.T) {
	server := ollamaChatServer(`not valid json`)
	defer server.Close()
	translator := llm.NewNlTransactionQueryTranslatorImpl(server.URL, "test-model")

	filter := translator.Translate(context.Background(), "anything")

	if filter != (query.TransactionFilter{}) {
		t.Fatalf("Translate() = %+v, want empty filter", filter)
	}
}

func TestNlTransactionQueryTranslatorImpl_Translate_OllamaCallFails_FallsBackToNoFilterRatherThanPanicking(t *testing.T) {
	translator := llm.NewNlTransactionQueryTranslatorImpl(unreachableServer(), "test-model")

	filter := translator.Translate(context.Background(), "anything")

	if filter != (query.TransactionFilter{}) {
		t.Fatalf("Translate() = %+v, want empty filter", filter)
	}
}

func TestNlTransactionQueryTranslatorImpl_Translate_OllamaRespondsWithNonOKStatus_FallsBackToNoFilter(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()
	translator := llm.NewNlTransactionQueryTranslatorImpl(server.URL, "test-model")

	filter := translator.Translate(context.Background(), "anything")

	if filter != (query.TransactionFilter{}) {
		t.Fatalf("Translate() = %+v, want empty filter", filter)
	}
}
