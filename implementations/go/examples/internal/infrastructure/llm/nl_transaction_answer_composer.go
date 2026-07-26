package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/example/account-service/internal/application/query"
)

// composeSystemPrompt instructs the model to answer only from the supplied
// data and to mirror the question's language — a small self-hosted model
// (qwen2.5:1.5b) is not guaranteed to follow the language-matching part
// reliably, but grounding in only the listed transactions is the
// correctness property this pipeline actually depends on.
const composeSystemPrompt = "You answer a user's question about their own bank account transactions using ONLY " +
	"the transaction data listed below — never mention or infer a transaction that isn't in that list. " +
	"Concisely (2-3 sentences). If the listed data doesn't contain enough information to answer (e.g. it's " +
	"empty), say so plainly instead of guessing.\n" +
	"IMPORTANT: detect the language the question itself is written in, and write your entire answer in that " +
	"same language — e.g. a Korean question always gets a Korean answer, an English question always gets an " +
	"English answer, regardless of what language this instruction or the transaction data is in."

// NlTransactionAnswerComposerImpl is the real implementation of
// query.NlTransactionAnswerComposer — a Technical Service wrapping a
// self-hosted Ollama call, without a JSON-schema-constrained response
// since the output here is free-form prose, not a structured value the
// caller parses.
type NlTransactionAnswerComposerImpl struct {
	httpClient *http.Client
	baseURL    string
	model      string
}

// NewNlTransactionAnswerComposerImpl builds the composer from an
// already-resolved Ollama base URL and model id — see
// config.OllamaBaseURL/config.LLMModel.
func NewNlTransactionAnswerComposerImpl(baseURL, model string) *NlTransactionAnswerComposerImpl {
	return &NlTransactionAnswerComposerImpl{
		httpClient: &http.Client{Timeout: 10 * time.Second},
		baseURL:    baseURL,
		model:      model,
	}
}

// Compose never returns an error: on any failure (network error, non-2xx
// response, malformed output, empty content) it logs a warning and falls
// back to a plain templated summary describing the same data a working
// call would have been grounded in, so an Ollama/network outage never
// blocks the question from getting *an* answer, even a plain one.
func (c *NlTransactionAnswerComposerImpl) Compose(ctx context.Context, question string, transactions []query.TransactionSummary) string {
	body, err := json.Marshal(chatRequest{
		Model:  c.model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: composeSystemPrompt},
			{Role: "user", Content: fmt.Sprintf("Question: %s\n\nTransactions:\n%s", question, formatTransactions(transactions))},
		},
	})
	if err != nil {
		slog.Warn("answer composition request could not be built, using fallback", "error", err)
		return fallbackAnswer(transactions)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		slog.Warn("answer composition request could not be built, using fallback", "error", err)
		return fallbackAnswer(transactions)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		slog.Warn("answer composition failed, using fallback", "error", err)
		return fallbackAnswer(transactions)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.Warn("answer composition failed, using fallback", "status", resp.StatusCode)
		return fallbackAnswer(transactions)
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.Warn("answer composition response could not be read, using fallback", "error", err)
		return fallbackAnswer(transactions)
	}

	var parsed chatResponse
	if err := json.Unmarshal(respBody, &parsed); err != nil {
		slog.Warn("answer composition returned malformed output, using fallback", "error", err)
		return fallbackAnswer(transactions)
	}

	content := strings.TrimSpace(parsed.Message.Content)
	if content == "" {
		slog.Warn("answer composition returned no content, using fallback")
		return fallbackAnswer(transactions)
	}
	return content
}

func formatTransactions(transactions []query.TransactionSummary) string {
	if len(transactions) == 0 {
		return "(no matching transactions)"
	}
	lines := make([]string, len(transactions))
	for i, t := range transactions {
		lines[i] = fmt.Sprintf("- %s %d %s on %s", t.Type, t.Amount.Amount, t.Amount.Currency, t.CreatedAt.Format("2006-01-02"))
	}
	return strings.Join(lines, "\n")
}

// fallbackAnswer is a plain, non-blocking fallback used whenever the LLM
// call fails — describes the same data a working call would have been
// grounded in, just without natural-language phrasing.
func fallbackAnswer(transactions []query.TransactionSummary) string {
	if len(transactions) == 0 {
		return "No matching transactions were found."
	}
	return fmt.Sprintf("Found %d matching transaction(s):\n%s", len(transactions), formatTransactions(transactions))
}
