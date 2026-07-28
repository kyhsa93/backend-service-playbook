package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	appevent "github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/domain/payment"
)

// refundReasonCategories is the fixed set the model must choose from — also
// embedded in the structured-output JSON schema below so Ollama's
// grammar-constrained decoding itself constrains the response to one of
// these strings.
var refundReasonCategories = []payment.RefundReasonCategory{
	payment.RefundReasonCategoryDefectiveProduct,
	payment.RefundReasonCategoryWrongItem,
	payment.RefundReasonCategoryNotAsDescribed,
	payment.RefundReasonCategoryChangedMind,
	payment.RefundReasonCategoryLateDelivery,
	payment.RefundReasonCategoryDuplicateCharge,
	payment.RefundReasonCategoryOther,
}

// fallbackRefundReasonCategory is a technical-infrastructure concern
// degrading to a safe default, not a domain error — this is a best-effort
// ops-analytics enrichment, not a concern that can affect
// EvaluateRefundEligibility's judgment, so a classification failure (the LLM
// call itself, or an out-of-taxonomy answer) degrades to OTHER rather than
// ever blocking or retrying indefinitely. The same posture as
// TransactionAutoCategorizerImpl falling back to OTHER.
const fallbackRefundReasonCategory = payment.RefundReasonCategoryOther

// refundReasonChatRequest/refundReasonResponseFormat mirror Ollama's
// /api/chat wire format the same way categorizeChatRequest/
// categorizeResponseFormat in transaction_auto_categorizer.go do, just
// classifying a refund reason instead of a transaction merchant — kept as
// separate types rather than reusing categorizeResponseFormat's fixed
// Properties shape, since that one is specific to
// TransactionAutoCategorizerImpl's job.
type refundReasonChatRequest struct {
	Model    string                     `json:"model"`
	Stream   bool                       `json:"stream"`
	Messages []chatMessage              `json:"messages"`
	Format   refundReasonResponseFormat `json:"format"`
}

type refundReasonResponseFormat struct {
	Type                 string                               `json:"type"`
	Properties           refundReasonResponseFormatProperties `json:"properties"`
	Required             []string                             `json:"required"`
	AdditionalProperties bool                                 `json:"additionalProperties"`
}

type refundReasonResponseFormatProperties struct {
	Category responseFormatField `json:"category"`
}

// refundReasonOutput mirrors refundReasonResponseFormat above — used only to
// unmarshal the model's response content.
type refundReasonOutput struct {
	Category string `json:"category"`
}

// RefundReasonClassifierImpl is the real implementation of
// event.RefundReasonClassifier — a Technical Service wrapping a self-hosted
// Ollama call, the same self-hosted qwen2.5:1.5b setup as
// TransactionAutoCategorizerImpl, just a different prompt/schema for a
// different job (classifying a refund's stated reason instead of a
// transaction's merchant name).
type RefundReasonClassifierImpl struct {
	httpClient *http.Client
	baseURL    string
	model      string
}

var _ appevent.RefundReasonClassifier = (*RefundReasonClassifierImpl)(nil)

// NewRefundReasonClassifierImpl builds the classifier from an
// already-resolved Ollama base URL and model id — see
// config.OllamaBaseURL/config.LLMModel.
func NewRefundReasonClassifierImpl(baseURL, model string) *RefundReasonClassifierImpl {
	return &RefundReasonClassifierImpl{
		httpClient: &http.Client{Timeout: 10 * time.Second},
		baseURL:    baseURL,
		model:      model,
	}
}

func buildRefundReasonSystemPrompt() string {
	return "You classify a customer's stated refund reason into exactly one category, for internal reporting only " +
		"(this never affects whether the refund is approved). Categories: " + strings.Join(refundReasonCategoryStrings(), ", ") +
		". Use OTHER only when none of the other categories plausibly fit. Respond only through the given schema."
}

// Classify never returns an error: on any failure (network error, non-2xx
// response, malformed output, unknown category) it logs a warning and
// returns fallbackRefundReasonCategory instead, so an Ollama/network outage
// never blocks — or forever retries — ClassifyRefundReasonEventHandler's
// reaction.
func (c *RefundReasonClassifierImpl) Classify(ctx context.Context, reason string) payment.RefundReasonCategory {
	body, err := json.Marshal(refundReasonChatRequest{
		Model:  c.model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: buildRefundReasonSystemPrompt()},
			{Role: "user", Content: reason},
		},
		Format: refundReasonResponseFormat{
			Type: "object",
			Properties: refundReasonResponseFormatProperties{
				Category: responseFormatField{Type: "string", Enum: refundReasonCategoryStrings()},
			},
			Required:             []string{"category"},
			AdditionalProperties: false,
		},
	})
	if err != nil {
		slog.WarnContext(ctx, "refund reason classification request could not be built, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		slog.WarnContext(ctx, "refund reason classification request could not be built, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		slog.WarnContext(ctx, "refund reason classification failed, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.WarnContext(ctx, "refund reason classification failed, using fallback category", "status", resp.StatusCode)
		return fallbackRefundReasonCategory
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.WarnContext(ctx, "refund reason classification response could not be read, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}

	var parsedResponse chatResponse
	if err := json.Unmarshal(respBody, &parsedResponse); err != nil {
		slog.WarnContext(ctx, "refund reason classification returned malformed output, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}
	if parsedResponse.Message.Content == "" {
		slog.WarnContext(ctx, "refund reason classification returned no content, using fallback category")
		return fallbackRefundReasonCategory
	}

	var parsed refundReasonOutput
	if err := json.Unmarshal([]byte(parsedResponse.Message.Content), &parsed); err != nil {
		slog.WarnContext(ctx, "refund reason classification returned malformed content, using fallback category", "error", err)
		return fallbackRefundReasonCategory
	}

	if !isValidRefundReasonCategory(payment.RefundReasonCategory(parsed.Category)) {
		return fallbackRefundReasonCategory
	}
	return payment.RefundReasonCategory(parsed.Category)
}

func isValidRefundReasonCategory(candidate payment.RefundReasonCategory) bool {
	for _, valid := range refundReasonCategories {
		if candidate == valid {
			return true
		}
	}
	return false
}

func refundReasonCategoryStrings() []string {
	out := make([]string, len(refundReasonCategories))
	for i, c := range refundReasonCategories {
		out[i] = string(c)
	}
	return out
}
