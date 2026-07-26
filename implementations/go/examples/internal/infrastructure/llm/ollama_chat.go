// Package llm holds the real, LLM-backed implementations of the Technical
// Service interfaces declared in internal/application/query (e.g.
// NlTransactionQueryTranslator/NlTransactionAnswerComposer — see
// docs/architecture/domain-service.md's structured-data RAG example). Both
// implementations talk to Ollama's native /api/chat endpoint over plain
// net/http (Ollama has no official Go client), so this file holds the
// wire-format types they share.
package llm

// chatMessage mirrors the shape Ollama's /api/chat endpoint expects for
// each entry in "messages".
type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// chatRequest mirrors Ollama's POST /api/chat request body. Format is a
// pointer so it's omitted entirely (no "format" key at all) when a caller
// wants a free-form response rather than one constrained to a JSON schema
// — see NlTransactionAnswerComposerImpl.Compose, which leaves it nil.
type chatRequest struct {
	Model    string          `json:"model"`
	Stream   bool            `json:"stream"`
	Messages []chatMessage   `json:"messages"`
	Format   *responseFormat `json:"format,omitempty"`
}

// chatResponse mirrors only the fields of Ollama's POST /api/chat response
// body this package needs.
type chatResponse struct {
	Message struct {
		Content string `json:"content"`
	} `json:"message"`
}

// responseFormat is Ollama's native structured-output field — a raw JSON
// Schema that constrains decoding to match it (grammar-based — this
// guarantees syntactically valid JSON matching this shape regardless of
// model size; it does NOT guarantee the type/date judgment itself is
// reliable at small sizes). Only NlTransactionQueryTranslatorImpl uses
// this, since it needs a structured type/fromDate/toDate value it can
// parse.
type responseFormat struct {
	Type                 string                   `json:"type"`
	Properties           responseFormatProperties `json:"properties"`
	Required             []string                 `json:"required"`
	AdditionalProperties bool                     `json:"additionalProperties"`
}

type responseFormatProperties struct {
	Type     responseFormatField `json:"type"`
	FromDate responseFormatField `json:"fromDate"`
	ToDate   responseFormatField `json:"toDate"`
}

type responseFormatField struct {
	Type string   `json:"type"`
	Enum []string `json:"enum,omitempty"`
}
