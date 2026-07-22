import { Injectable, Logger } from '@nestjs/common'

import { getOllamaBaseUrl, getRefundClassifierModel } from '@/config/llm.config'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { RefundReasonCategory, RefundReasonClassification } from '@/payment/domain/refund-reason-classification'

const CATEGORIES: RefundReasonCategory[] = [
  'defective_product',
  'not_as_described',
  'duplicate_charge',
  'changed_mind',
  'fraud_suspected',
  'other'
]

// Used whenever the classification can't be trusted (Ollama unreachable, malformed output).
// A neutral 'other'/no-fraud-signal result never blocks the refund flow on its own —
// RefundEligibilityService's other checks still run against it.
const FALLBACK_CLASSIFICATION: RefundReasonClassification = { category: 'other', fraudRiskScore: 0 }

// Deliberately explicit and example-anchored — the classifier runs on a small, self-hosted
// model (qwen2.5:1.5b) that, tested live against this exact prompt shape, otherwise conflates
// ordinary billing complaints ("charged twice, refund the duplicate") with fraud_suspected.
// A calm, single-issue complaint is never fraud_suspected on its own; only report fraud when the
// text itself shows deception or denies placing the order.
const SYSTEM_PROMPT = 'You classify a customer refund request\'s free-text reason into exactly one category and a '
  + 'fraud-risk score from 0 to 1. Respond only through the given schema.\n'
  + 'Categories: defective_product (item broken/damaged/malfunctioning), not_as_described (wrong item or '
  + 'mismatched description), duplicate_charge (billed more than once for the same order), changed_mind (no '
  + 'longer wants the item, no product issue), fraud_suspected (the customer explicitly states they never placed '
  + 'the order, or the message itself shows deception/inconsistency), other.\n'
  + 'A plain, calm complaint about being billed twice is duplicate_charge with a LOW fraud score near 0 — it is '
  + 'not fraud_suspected. Only use fraud_suspected for signs of deception, not for an ordinary billing complaint.'

interface OllamaChatResponse {
  message?: { content?: string }
}

// Ollama's `format` field takes a raw JSON Schema and constrains decoding to match it (grammar-
// based — this guarantees syntactically valid JSON matching this shape regardless of model
// size; it does NOT guarantee the category/score judgment itself is reliable at small sizes,
// which is why the system prompt above is unusually explicit and example-anchored).
const RESPONSE_FORMAT = {
  type: 'object',
  properties: {
    category: { type: 'string', enum: CATEGORIES },
    fraudRiskScore: { type: 'number' }
  },
  required: ['category', 'fraudRiskScore'],
  additionalProperties: false
}

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call. Ollama (docker-compose.yml's ollama/ollama-init services) runs the open-source
// qwen2.5:1.5b model locally — no external API, no API key. Talks to Ollama's native /api/chat
// endpoint over plain HTTP rather than a vendor SDK, since Ollama has no official Node client.
// qwen2.5:1.5b (not the smaller 0.5b variant) was chosen after live-testing both: 0.5b
// misclassified plain billing complaints ("charged twice, refund the duplicate") as
// fraud_suspected with fraudRiskScore 1.0, which would wrongly reject a legitimate refund at
// the 0.7 threshold below. 1.5b is meaningfully more reliable while still under ~1GB.
@Injectable()
export class RefundReasonClassifierImpl extends RefundReasonClassifier {
  private readonly logger = new Logger(RefundReasonClassifierImpl.name)

  public async classify(reason: string): Promise<RefundReasonClassification> {
    try {
      const response = await fetch(`${getOllamaBaseUrl()}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: getRefundClassifierModel(),
          stream: false,
          messages: [
            { role: 'system', content: SYSTEM_PROMPT },
            { role: 'user', content: reason }
          ],
          format: RESPONSE_FORMAT
        })
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Refund reason classification failed, using fallback', status: response.status })
        return FALLBACK_CLASSIFICATION
      }

      const body = await response.json() as OllamaChatResponse
      const content = body.message?.content
      if (!content) return FALLBACK_CLASSIFICATION

      const parsed = JSON.parse(content) as { category: string; fraudRiskScore: number }
      if (!CATEGORIES.includes(parsed.category as RefundReasonCategory)) return FALLBACK_CLASSIFICATION

      return {
        category: parsed.category as RefundReasonCategory,
        fraudRiskScore: Math.min(1, Math.max(0, parsed.fraudRiskScore))
      }
    } catch (error) {
      // A classification failure is a technical-infrastructure concern, not a domain error — it
      // must never block a refund request. Swallow it here at the boundary and fall back.
      this.logger.warn({ message: 'Refund reason classification failed, using fallback', error: (error as Error).message })
      return FALLBACK_CLASSIFICATION
    }
  }
}
