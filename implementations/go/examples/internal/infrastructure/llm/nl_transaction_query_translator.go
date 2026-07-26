package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
)

// transactionTypes is the fixed set the model must choose from — also
// embedded in the structured-output JSON schema below (as "ANY" appended)
// so Ollama's grammar-constrained decoding itself constrains the response
// to one of these strings.
var transactionTypes = []account.TransactionType{
	account.TransactionTypeDeposit,
	account.TransactionTypeWithdrawal,
	account.TransactionTypeInterest,
}

// fallbackFilter degrades gracefully to "no narrowing" — AskTransactionHistoryHandler
// still runs account.Query's FindTransactions with no type/date
// constraint, so a translation failure never blocks the question from
// being answered against the requester's most recent transactions.
var fallbackFilter = query.TransactionFilter{}

// NlTransactionQueryTranslatorImpl is the real implementation of
// query.NlTransactionQueryTranslator — a Technical Service wrapping a
// self-hosted Ollama call, the same self-hosted qwen2.5:1.5b setup used
// elsewhere in this repo for LLM Technical Services.
type NlTransactionQueryTranslatorImpl struct {
	httpClient *http.Client
	baseURL    string
	model      string
}

// NewNlTransactionQueryTranslatorImpl builds the translator from an
// already-resolved Ollama base URL and model id — see
// config.OllamaBaseURL/config.LLMModel.
func NewNlTransactionQueryTranslatorImpl(baseURL, model string) *NlTransactionQueryTranslatorImpl {
	return &NlTransactionQueryTranslatorImpl{
		httpClient: &http.Client{Timeout: 10 * time.Second},
		baseURL:    baseURL,
		model:      model,
	}
}

func buildTranslateSystemPrompt() string {
	today := time.Now().Format("2006-01-02")
	return "You translate a user's natural-language question about their own bank account transaction history " +
		"into a structured JSON filter. Today's date is " + today + ". Resolve any relative date expression " +
		"(\"this month\", \"last week\") against that date.\n" +
		"Fields: \"type\" — DEPOSIT, WITHDRAWAL, INTEREST, or ANY if the question doesn't ask about a specific " +
		"type. \"fromDate\"/\"toDate\" — an ISO 8601 date (YYYY-MM-DD), or an empty string if the question " +
		"implies no date boundary on that side.\n" +
		"Only extract constraints the question actually states or clearly implies. Never invent a date range " +
		"or transaction type the question doesn't support. Respond only through the given schema."
}

// translationOutput mirrors the structured-output JSON schema below — used
// only to unmarshal the model's response content.
type translationOutput struct {
	Type     string `json:"type"`
	FromDate string `json:"fromDate"`
	ToDate   string `json:"toDate"`
}

// Translate never returns an error: on any failure (network error, non-2xx
// response, malformed output, unknown type, invalid date) it logs a
// warning and returns fallbackFilter instead, so an Ollama/network outage
// never blocks a question from being answered (AskTransactionHistoryHandler
// always gets a usable filter to pass to account.Query's FindTransactions).
func (t *NlTransactionQueryTranslatorImpl) Translate(ctx context.Context, question string) query.TransactionFilter {
	body, err := json.Marshal(chatRequest{
		Model:  t.model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: buildTranslateSystemPrompt()},
			{Role: "user", Content: question},
		},
		Format: &responseFormat{
			Type: "object",
			Properties: responseFormatProperties{
				Type:     responseFormatField{Type: "string", Enum: append(typeStrings(), "ANY")},
				FromDate: responseFormatField{Type: "string"},
				ToDate:   responseFormatField{Type: "string"},
			},
			Required:             []string{"type", "fromDate", "toDate"},
			AdditionalProperties: false,
		},
	})
	if err != nil {
		slog.Warn("transaction query translation request could not be built, using no filter", "error", err)
		return fallbackFilter
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.baseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		slog.Warn("transaction query translation request could not be built, using no filter", "error", err)
		return fallbackFilter
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := t.httpClient.Do(req)
	if err != nil {
		slog.Warn("transaction query translation failed, using no filter", "error", err)
		return fallbackFilter
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.Warn("transaction query translation failed, using no filter", "status", resp.StatusCode)
		return fallbackFilter
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.Warn("transaction query translation response could not be read, using no filter", "error", err)
		return fallbackFilter
	}

	var parsedResponse chatResponse
	if err := json.Unmarshal(respBody, &parsedResponse); err != nil {
		slog.Warn("transaction query translation returned malformed output, using no filter", "error", err)
		return fallbackFilter
	}
	if parsedResponse.Message.Content == "" {
		slog.Warn("transaction query translation returned no content, using no filter")
		return fallbackFilter
	}

	var parsed translationOutput
	if err := json.Unmarshal([]byte(parsedResponse.Message.Content), &parsed); err != nil {
		slog.Warn("transaction query translation returned malformed content, using no filter", "error", err)
		return fallbackFilter
	}

	filter := query.TransactionFilter{}
	if isValidType(account.TransactionType(parsed.Type)) {
		filter.Type = account.TransactionType(parsed.Type)
	}
	if isValidISODate(parsed.FromDate) {
		filter.FromDate = parsed.FromDate
	}
	if isValidISODate(parsed.ToDate) {
		filter.ToDate = parsed.ToDate
	}
	return filter
}

func isValidType(candidate account.TransactionType) bool {
	for _, valid := range transactionTypes {
		if candidate == valid {
			return true
		}
	}
	return false
}

// isValidISODate reports whether v parses as a real calendar date in
// YYYY-MM-DD form — time.Parse itself rejects out-of-range components
// (e.g. month 13, day 30 of February), so no separate regexp is needed.
func isValidISODate(v string) bool {
	if v == "" {
		return false
	}
	_, err := time.Parse("2006-01-02", v)
	return err == nil
}

func typeStrings() []string {
	out := make([]string, len(transactionTypes))
	for i, transactionType := range transactionTypes {
		out[i] = string(transactionType)
	}
	return out
}
