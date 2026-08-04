package test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
)

// forceLLMFailureMarker makes the fake Ollama below answer 500 whenever it
// appears anywhere in a request's user message. Tests that need to cover an
// LLM Technical Service's graceful-fallback path embed it in the natural
// input that reaches the prompt (question/merchantName/reason), so the
// forced outage is scoped to exactly that request — every other request in
// the suite keeps getting deterministic successful responses.
const forceLLMFailureMarker = "force-llm-500"

// fakeAnswerPrefix marks answers produced by the fake composer below —
// asserting on it proves the /transactions/ask answer really came through
// the LLM request/parse path (the composer's own fallback answer starts
// with "Found ... matching transaction(s)" instead).
const fakeAnswerPrefix = "FAKE-OLLAMA GROUNDED ANSWER:\n"

// fakeOllamaMessage/fakeOllamaChatRequest mirror just enough of Ollama's
// POST /api/chat request body to route on (the "format" JSON schema the
// structured-output callers send is irrelevant to routing and ignored).
type fakeOllamaMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type fakeOllamaChatRequest struct {
	Model    string              `json:"model"`
	Messages []fakeOllamaMessage `json:"messages"`
}

// newFakeOllamaServer starts an httptest.Server emulating the one Ollama
// endpoint every LLM Technical Service in internal/infrastructure/llm
// calls: POST /api/chat, non-streaming. It routes purely on recognizable
// markers in the prompt — the system message identifies WHICH service is
// calling (each service has a distinctive system prompt), and the user
// message picks WHAT deterministic content to answer with — so the e2e
// suite exercises each service's real HTTP request/response-parse path
// without a live model, and without any per-test mutable state.
func newFakeOllamaServer() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/chat" {
			http.NotFound(w, r)
			return
		}

		var req fakeOllamaChatRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "malformed chat request", http.StatusBadRequest)
			return
		}

		var systemContent, userContent string
		for _, m := range req.Messages {
			switch m.Role {
			case "system":
				systemContent = m.Content
			case "user":
				userContent = m.Content
			}
		}

		if strings.Contains(userContent, forceLLMFailureMarker) {
			http.Error(w, "forced failure for fallback-path coverage", http.StatusInternalServerError)
			return
		}

		content, ok := routeFakeOllamaContent(systemContent, userContent)
		if !ok {
			// An unrecognized system prompt means a new LLM Technical
			// Service was added without teaching this fake about it — fail
			// loudly (the caller's fallback assertion will surface it).
			http.Error(w, "fake Ollama does not recognize this system prompt", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"message": map[string]string{"role": "assistant", "content": content},
		})
	}))
}

// routeFakeOllamaContent picks the deterministic response content for one
// chat request. Each branch matches a distinctive phrase from the real
// system prompt built in internal/infrastructure/llm, and returns content
// in exactly the shape that service parses (structured JSON for the
// translator/categorizer/classifier, free-form prose for the composer).
func routeFakeOllamaContent(systemContent, userContent string) (string, bool) {
	switch {
	// NlTransactionQueryTranslatorImpl — buildTranslateSystemPrompt.
	case strings.Contains(systemContent, "translate a user's natural-language question"):
		if strings.Contains(strings.ToLower(userContent), "deposit") {
			return `{"type":"DEPOSIT","fromDate":"","toDate":""}`, true
		}
		return `{"type":"ANY","fromDate":"","toDate":""}`, true

	// NlTransactionAnswerComposerImpl — composeSystemPrompt. Echo the
	// grounding data back so the caller can assert the retrieved
	// transactions really reached the prompt (and non-matching ones did
	// not).
	case strings.Contains(systemContent, "answer a user's question about their own bank account transactions"):
		grounding := userContent
		if _, after, found := strings.Cut(userContent, "Transactions:\n"); found {
			grounding = after
		}
		return fakeAnswerPrefix + grounding, true

	// TransactionAutoCategorizerImpl — buildCategorizeSystemPrompt. User
	// content is "Merchant: <name>\nAmount: <n>".
	case strings.Contains(systemContent, "classify a bank withdrawal"):
		if strings.Contains(userContent, "Starbucks") {
			return `{"category":"FOOD"}`, true
		}
		return `{"category":"OTHER"}`, true

	// RefundReasonClassifierImpl — buildRefundReasonSystemPrompt. User
	// content is the refund's stated reason verbatim.
	case strings.Contains(systemContent, "classify a customer's stated refund reason"):
		lowered := strings.ToLower(userContent)
		switch {
		case strings.Contains(lowered, "arrived broken"):
			return `{"category":"DEFECTIVE_PRODUCT"}`, true
		case strings.Contains(lowered, "changed my mind"):
			return `{"category":"CHANGED_MIND"}`, true
		}
		return `{"category":"OTHER"}`, true
	}
	return "", false
}
