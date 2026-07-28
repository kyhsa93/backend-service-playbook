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

	appevent "github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/domain/account"
)

// categories is the fixed set the model must choose from — also embedded in
// the structured-output JSON schema below so Ollama's grammar-constrained
// decoding itself constrains the response to one of these strings.
var categories = []account.TransactionCategory{
	account.TransactionCategoryFood,
	account.TransactionCategoryTransport,
	account.TransactionCategoryShopping,
	account.TransactionCategoryHousing,
	account.TransactionCategoryMedical,
	account.TransactionCategoryEntertainment,
	account.TransactionCategoryUtilities,
	account.TransactionCategoryOther,
}

// fallbackCategory is a technical-infrastructure concern degrading to a
// safe default, not a domain error — this is a best-effort enrichment, not
// a financial correctness concern, so a classification failure (the LLM
// call itself, or an out-of-taxonomy answer) degrades to OTHER rather than
// ever blocking or retrying indefinitely. The same posture as
// NlTransactionQueryTranslatorImpl falling back to no filter.
const fallbackCategory = account.TransactionCategoryOther

// categorizeChatRequest/categorizeResponseFormat mirror Ollama's /api/chat
// wire format the same way chatRequest/responseFormat in ollama_chat.go do,
// just with a category-only schema instead of type/fromDate/toDate — kept
// as separate types rather than reusing responseFormat's fixed
// Properties shape, since that one is specific to
// NlTransactionQueryTranslatorImpl's filter fields.
type categorizeChatRequest struct {
	Model    string                   `json:"model"`
	Stream   bool                     `json:"stream"`
	Messages []chatMessage            `json:"messages"`
	Format   categorizeResponseFormat `json:"format"`
}

type categorizeResponseFormat struct {
	Type                 string                             `json:"type"`
	Properties           categorizeResponseFormatProperties `json:"properties"`
	Required             []string                           `json:"required"`
	AdditionalProperties bool                               `json:"additionalProperties"`
}

type categorizeResponseFormatProperties struct {
	Category responseFormatField `json:"category"`
}

// categorizeOutput mirrors categorizeResponseFormat above — used only to
// unmarshal the model's response content.
type categorizeOutput struct {
	Category string `json:"category"`
}

// TransactionAutoCategorizerImpl is the real implementation of
// event.TransactionAutoCategorizer — a Technical Service wrapping a
// self-hosted Ollama call, the same self-hosted qwen2.5:1.5b setup as
// NlTransactionQueryTranslatorImpl/NlTransactionAnswerComposerImpl, just a
// different prompt/schema for a different job (classification instead of
// query translation or answer generation).
type TransactionAutoCategorizerImpl struct {
	httpClient *http.Client
	baseURL    string
	model      string
}

var _ appevent.TransactionAutoCategorizer = (*TransactionAutoCategorizerImpl)(nil)

// NewTransactionAutoCategorizerImpl builds the categorizer from an
// already-resolved Ollama base URL and model id — see
// config.OllamaBaseURL/config.LLMModel.
func NewTransactionAutoCategorizerImpl(baseURL, model string) *TransactionAutoCategorizerImpl {
	return &TransactionAutoCategorizerImpl{
		httpClient: &http.Client{Timeout: 10 * time.Second},
		baseURL:    baseURL,
		model:      model,
	}
}

func buildCategorizeSystemPrompt() string {
	return "You classify a bank withdrawal into exactly one spending category based on its payee/merchant name and " +
		"amount. Categories: " + strings.Join(categoryStrings(), ", ") + ". Use OTHER only when none of the " +
		"other categories plausibly fit. Respond only through the given schema."
}

// Categorize never returns an error: on any failure (network error, non-2xx
// response, malformed output, unknown category) it logs a warning and
// returns fallbackCategory instead, so an Ollama/network outage never
// blocks — or forever retries — CategorizeTransactionEventHandler's reaction.
func (c *TransactionAutoCategorizerImpl) Categorize(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
	body, err := json.Marshal(categorizeChatRequest{
		Model:  c.model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: buildCategorizeSystemPrompt()},
			{Role: "user", Content: fmt.Sprintf("Merchant: %s\nAmount: %d", merchantName, amount)},
		},
		Format: categorizeResponseFormat{
			Type: "object",
			Properties: categorizeResponseFormatProperties{
				Category: responseFormatField{Type: "string", Enum: categoryStrings()},
			},
			Required:             []string{"category"},
			AdditionalProperties: false,
		},
	})
	if err != nil {
		slog.WarnContext(ctx, "transaction categorization request could not be built, using fallback category", "error", err)
		return fallbackCategory
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		slog.WarnContext(ctx, "transaction categorization request could not be built, using fallback category", "error", err)
		return fallbackCategory
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		slog.WarnContext(ctx, "transaction categorization failed, using fallback category", "error", err)
		return fallbackCategory
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.WarnContext(ctx, "transaction categorization failed, using fallback category", "status", resp.StatusCode)
		return fallbackCategory
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.WarnContext(ctx, "transaction categorization response could not be read, using fallback category", "error", err)
		return fallbackCategory
	}

	var parsedResponse chatResponse
	if err := json.Unmarshal(respBody, &parsedResponse); err != nil {
		slog.WarnContext(ctx, "transaction categorization returned malformed output, using fallback category", "error", err)
		return fallbackCategory
	}
	if parsedResponse.Message.Content == "" {
		slog.WarnContext(ctx, "transaction categorization returned no content, using fallback category")
		return fallbackCategory
	}

	var parsed categorizeOutput
	if err := json.Unmarshal([]byte(parsedResponse.Message.Content), &parsed); err != nil {
		slog.WarnContext(ctx, "transaction categorization returned malformed content, using fallback category", "error", err)
		return fallbackCategory
	}

	if !isValidCategory(account.TransactionCategory(parsed.Category)) {
		return fallbackCategory
	}
	return account.TransactionCategory(parsed.Category)
}

func isValidCategory(candidate account.TransactionCategory) bool {
	for _, valid := range categories {
		if candidate == valid {
			return true
		}
	}
	return false
}

func categoryStrings() []string {
	out := make([]string, len(categories))
	for i, c := range categories {
		out[i] = string(c)
	}
	return out
}
