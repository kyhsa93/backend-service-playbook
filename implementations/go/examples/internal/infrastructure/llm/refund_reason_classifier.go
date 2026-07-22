// Package llm holds the real, LLM-backed implementation of the Technical
// Service interfaces declared in internal/application/command (e.g.
// RefundReasonClassifier) — see docs/architecture/domain-service.md.
package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"

	"github.com/example/account-service/internal/domain/payment"
)

// categories is the fixed set the model must choose from — also embedded in
// the structured-output JSON schema below so Ollama's grammar-constrained
// decoding itself constrains the response to one of these strings.
var categories = []payment.RefundReasonCategory{
	payment.RefundReasonDefectiveProduct,
	payment.RefundReasonNotAsDescribed,
	payment.RefundReasonDuplicateCharge,
	payment.RefundReasonChangedMind,
	payment.RefundReasonFraudSuspected,
	payment.RefundReasonOther,
}

// fallbackClassification is used whenever the classification can't be
// trusted (Ollama unreachable, malformed output). A neutral
// other/no-fraud-signal result never blocks the refund flow on its own —
// payment.EvaluateRefundEligibility's other checks still run against it.
var fallbackClassification = payment.RefundReasonClassification{Category: payment.RefundReasonOther, FraudRiskScore: 0}

// Deliberately explicit and example-anchored — the classifier runs on a small, self-hosted
// model (qwen2.5:1.5b) that, tested live against this exact prompt shape, otherwise conflates
// ordinary billing complaints ("charged twice, refund the duplicate") with fraud_suspected.
// A calm, single-issue complaint is never fraud_suspected on its own; only report fraud when the
// text itself shows deception or denies placing the order.
const systemPrompt = "You classify a customer refund request's free-text reason into exactly one category and a " +
	"fraud-risk score from 0 to 1. Respond only through the given schema.\n" +
	"Categories: defective_product (item broken/damaged/malfunctioning), not_as_described (wrong item or " +
	"mismatched description), duplicate_charge (billed more than once for the same order), changed_mind (no " +
	"longer wants the item, no product issue), fraud_suspected (the customer explicitly states they never placed " +
	"the order, or the message itself shows deception/inconsistency), other.\n" +
	"A plain, calm complaint about being billed twice is duplicate_charge with a LOW fraud score near 0 — it is " +
	"not fraud_suspected. Only use fraud_suspected for signs of deception, not for an ordinary billing complaint."

// RefundReasonClassifierImpl is the real implementation of
// command.RefundReasonClassifier — a Technical Service wrapping a
// self-hosted Ollama call. Ollama (docker-compose.yml's ollama/ollama-init
// services) runs the open-source qwen2.5:1.5b model locally — no external
// API, no API key. Talks to Ollama's native /api/chat endpoint over plain
// net/http rather than a vendor SDK, since Ollama has no official Go client.
// qwen2.5:1.5b (not the smaller 0.5b variant) was chosen after live-testing
// both: 0.5b misclassified plain billing complaints ("charged twice, refund
// the duplicate") as fraud_suspected with fraudRiskScore 1.0, which would
// wrongly reject a legitimate refund at the 0.7 threshold in
// payment.EvaluateRefundEligibility. 1.5b is meaningfully more reliable
// while still under ~1GB.
type RefundReasonClassifierImpl struct {
	httpClient *http.Client
	baseURL    string
	model      string
}

// NewRefundReasonClassifierImpl builds the classifier from an already-resolved Ollama base URL
// and model id — see config.OllamaBaseURL/RefundClassifierModel.
func NewRefundReasonClassifierImpl(baseURL, model string) *RefundReasonClassifierImpl {
	return &RefundReasonClassifierImpl{
		httpClient: &http.Client{},
		baseURL:    baseURL,
		model:      model,
	}
}

// chatMessage mirrors the shape Ollama's /api/chat endpoint expects for each entry in
// "messages".
type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// responseFormat is Ollama's native structured-output field — a raw JSON Schema that
// constrains decoding to match it (grammar-based — this guarantees syntactically valid JSON
// matching this shape regardless of model size; it does NOT guarantee the category/score
// judgment itself is reliable at small sizes, which is why systemPrompt above is unusually
// explicit and example-anchored).
type responseFormat struct {
	Type                 string                   `json:"type"`
	Properties           responseFormatProperties `json:"properties"`
	Required             []string                 `json:"required"`
	AdditionalProperties bool                     `json:"additionalProperties"`
}

type responseFormatProperties struct {
	Category       responseFormatField `json:"category"`
	FraudRiskScore responseFormatField `json:"fraudRiskScore"`
}

type responseFormatField struct {
	Type string   `json:"type"`
	Enum []string `json:"enum,omitempty"`
}

// chatRequest mirrors Ollama's POST /api/chat request body.
type chatRequest struct {
	Model    string         `json:"model"`
	Stream   bool           `json:"stream"`
	Messages []chatMessage  `json:"messages"`
	Format   responseFormat `json:"format"`
}

// chatResponse mirrors only the fields of Ollama's POST /api/chat response body this
// implementation needs.
type chatResponse struct {
	Message struct {
		Content string `json:"content"`
	} `json:"message"`
}

// classificationOutput mirrors the structured-output JSON schema above —
// used only to unmarshal the model's response content.
type classificationOutput struct {
	Category       string  `json:"category"`
	FraudRiskScore float64 `json:"fraudRiskScore"`
}

// Classify never returns an error: on any failure (network error, non-2xx
// response, malformed output, unknown category) it logs a warning and
// returns fallbackClassification instead of propagating an error, so an
// Ollama/network outage never blocks a refund request (RequestRefundHandler
// always gets a usable classification to pass to
// payment.EvaluateRefundEligibility).
func (c *RefundReasonClassifierImpl) Classify(ctx context.Context, reason string) payment.RefundReasonClassification {
	body, err := json.Marshal(chatRequest{
		Model:  c.model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: systemPrompt},
			{Role: "user", Content: reason},
		},
		Format: responseFormat{
			Type: "object",
			Properties: responseFormatProperties{
				Category:       responseFormatField{Type: "string", Enum: categoryStrings()},
				FraudRiskScore: responseFormatField{Type: "number"},
			},
			Required:             []string{"category", "fraudRiskScore"},
			AdditionalProperties: false,
		},
	})
	if err != nil {
		slog.Warn("refund reason classification request could not be built, using fallback", "error", err)
		return fallbackClassification
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		slog.Warn("refund reason classification request could not be built, using fallback", "error", err)
		return fallbackClassification
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		slog.Warn("refund reason classification failed, using fallback", "error", err)
		return fallbackClassification
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.Warn("refund reason classification failed, using fallback", "status", resp.StatusCode)
		return fallbackClassification
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.Warn("refund reason classification response could not be read, using fallback", "error", err)
		return fallbackClassification
	}

	var parsedResponse chatResponse
	if err := json.Unmarshal(respBody, &parsedResponse); err != nil {
		slog.Warn("refund reason classification returned malformed output, using fallback", "error", err)
		return fallbackClassification
	}
	if parsedResponse.Message.Content == "" {
		slog.Warn("refund reason classification returned no content, using fallback")
		return fallbackClassification
	}

	var parsed classificationOutput
	if err := json.Unmarshal([]byte(parsedResponse.Message.Content), &parsed); err != nil {
		slog.Warn("refund reason classification returned malformed content, using fallback", "error", err)
		return fallbackClassification
	}

	category := payment.RefundReasonCategory(parsed.Category)
	if !isValidCategory(category) {
		slog.Warn("refund reason classification returned an unknown category, using fallback", "category", parsed.Category)
		return fallbackClassification
	}

	return payment.RefundReasonClassification{Category: category, FraudRiskScore: clampUnit(parsed.FraudRiskScore)}
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
