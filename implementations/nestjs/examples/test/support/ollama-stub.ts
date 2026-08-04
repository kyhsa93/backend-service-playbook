// Helpers for stubbing the self-hosted Ollama LLM with nock in E2E tests.
//
// Every LLM Technical Service in src (NlTransactionQueryTranslatorImpl,
// NlTransactionAnswerComposerImpl, TransactionAutoCategorizerImpl, RefundReasonClassifierImpl)
// POSTs to the same `${OLLAMA_BASE_URL}/api/chat` endpoint, so interceptors are routed by the
// system prompt each service sends — a body predicate per service. OLLAMA_BASE_URL points at a
// fake origin that never resolves in DNS, which keeps two properties: nock only ever intercepts
// LLM traffic (testcontainers traffic to localhost is untouched — no nock.disableNetConnect),
// and if an LLM call escapes the interceptors it fails fast with a DNS error and exercises the
// service's graceful fallback instead of hitting anything real.

export const FAKE_OLLAMA_ORIGIN = 'http://ollama.test.local:11434'

export interface OllamaChatRequestBody {
  model: string
  stream: boolean
  messages: { role: string; content: string }[]
  format?: unknown
}

function systemPrompt(body: OllamaChatRequestBody): string {
  return body.messages?.find((message) => message.role === 'system')?.content ?? ''
}

export function userPrompt(body: OllamaChatRequestBody): string {
  return body.messages?.find((message) => message.role === 'user')?.content ?? ''
}

// Matches NlTransactionQueryTranslatorImpl (question -> structured transaction filter).
export function isQueryTranslatorRequest(body: OllamaChatRequestBody): boolean {
  return systemPrompt(body).includes('structured JSON filter')
}

// Matches NlTransactionAnswerComposerImpl (retrieved transactions -> grounded answer).
export function isAnswerComposerRequest(body: OllamaChatRequestBody): boolean {
  return systemPrompt(body).includes('transaction data listed below')
}

// Matches TransactionAutoCategorizerImpl (merchant name -> spending category).
export function isAutoCategorizerRequest(body: OllamaChatRequestBody): boolean {
  return systemPrompt(body).includes('classify a bank withdrawal')
}

// Matches RefundReasonClassifierImpl (refund reason -> reason category).
export function isRefundReasonClassifierRequest(body: OllamaChatRequestBody): boolean {
  return systemPrompt(body).includes('refund reason')
}

// Shapes a reply the way Ollama's non-streaming /api/chat responds.
export function ollamaChatReply(content: string): { message: { content: string } } {
  return { message: { content } }
}
