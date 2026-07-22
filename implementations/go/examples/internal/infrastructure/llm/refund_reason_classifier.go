// Package llm holds the real, LLM-backed implementation of the Technical
// Service interfaces declared in internal/application/command (e.g.
// RefundReasonClassifier) — see docs/architecture/domain-service.md.
package llm

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"

	"github.com/example/account-service/internal/domain/payment"
)

// categories is the fixed set the model must choose from — also embedded in
// the structured-output JSON schema below so the API itself constrains the
// response to one of these strings.
var categories = []payment.RefundReasonCategory{
	payment.RefundReasonDefectiveProduct,
	payment.RefundReasonNotAsDescribed,
	payment.RefundReasonDuplicateCharge,
	payment.RefundReasonChangedMind,
	payment.RefundReasonFraudSuspected,
	payment.RefundReasonOther,
}

// fallbackClassification is used whenever the classification can't be
// trusted (API error, refusal, malformed output). A neutral
// other/no-fraud-signal result never blocks the refund flow on its own —
// payment.EvaluateRefundEligibility's other checks still run against it.
var fallbackClassification = payment.RefundReasonClassification{Category: payment.RefundReasonOther, FraudRiskScore: 0}

const systemPrompt = "You classify a customer refund request's free-text reason. Respond only through the given " +
	"schema. Base fraudRiskScore purely on linguistic signals in the text itself (vagueness, internal " +
	"inconsistency, urgency/pressure language, or an admission unrelated to the product) — never infer a high " +
	"score just because the category is fraud_suspected, and never infer a low score just because it isn't."

// RefundReasonClassifierImpl is the real implementation of
// command.RefundReasonClassifier — a Technical Service wrapping a Claude API
// call. The API key and model are resolved once by internal/config (see
// config.LoadAnthropicAPIKey/RefundClassifierModel — config.md, "no direct
// env var access outside config") and injected via the constructor; this
// package never reads ANTHROPIC_API_KEY/REFUND_CLASSIFIER_MODEL itself.
type RefundReasonClassifierImpl struct {
	client anthropic.Client
	model  string
}

// NewRefundReasonClassifierImpl builds the classifier from an already-resolved API key and
// model id — see config.LoadAnthropicAPIKey/RefundClassifierModel.
func NewRefundReasonClassifierImpl(apiKey, model string) *RefundReasonClassifierImpl {
	return &RefundReasonClassifierImpl{
		client: anthropic.NewClient(option.WithAPIKey(apiKey)),
		model:  model,
	}
}

// classificationOutput mirrors the structured-output JSON schema below —
// used only to unmarshal the model's response text.
type classificationOutput struct {
	Category       string  `json:"category"`
	FraudRiskScore float64 `json:"fraudRiskScore"`
}

// Classify never returns an error: on any failure (API error, malformed
// output, refusal, network error) it logs a warning and returns
// fallbackClassification instead of propagating an error, so an LLM/network
// outage never blocks a refund request (RequestRefundHandler always gets a
// usable classification to pass to payment.EvaluateRefundEligibility).
func (c *RefundReasonClassifierImpl) Classify(ctx context.Context, reason string) payment.RefundReasonClassification {
	response, err := c.client.Messages.New(ctx, anthropic.MessageNewParams{
		Model:     c.model,
		MaxTokens: 256,
		System:    []anthropic.TextBlockParam{{Text: systemPrompt}},
		Messages:  []anthropic.MessageParam{anthropic.NewUserMessage(anthropic.NewTextBlock(reason))},
		OutputConfig: anthropic.OutputConfigParam{
			Format: anthropic.JSONOutputFormatParam{
				Schema: map[string]any{
					"type": "object",
					"properties": map[string]any{
						"category":       map[string]any{"type": "string", "enum": categoryStrings()},
						"fraudRiskScore": map[string]any{"type": "number"},
					},
					"required":             []string{"category", "fraudRiskScore"},
					"additionalProperties": false,
				},
			},
		},
	})
	if err != nil {
		slog.Warn("refund reason classification failed, using fallback", "error", err)
		return fallbackClassification
	}

	if response.StopReason == anthropic.StopReasonRefusal {
		slog.Warn("refund reason classification was refused, using fallback")
		return fallbackClassification
	}

	text, ok := firstText(response)
	if !ok {
		slog.Warn("refund reason classification returned no text block, using fallback")
		return fallbackClassification
	}

	var parsed classificationOutput
	if err := json.Unmarshal([]byte(text), &parsed); err != nil {
		slog.Warn("refund reason classification returned malformed output, using fallback", "error", err)
		return fallbackClassification
	}

	category := payment.RefundReasonCategory(parsed.Category)
	if !isValidCategory(category) {
		slog.Warn("refund reason classification returned an unknown category, using fallback", "category", parsed.Category)
		return fallbackClassification
	}

	return payment.RefundReasonClassification{Category: category, FraudRiskScore: clampUnit(parsed.FraudRiskScore)}
}

func firstText(response *anthropic.Message) (string, bool) {
	for _, block := range response.Content {
		if tb, ok := block.AsAny().(anthropic.TextBlock); ok {
			return tb.Text, true
		}
	}
	return "", false
}

func isValidCategory(c payment.RefundReasonCategory) bool {
	for _, valid := range categories {
		if c == valid {
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

func clampUnit(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}
