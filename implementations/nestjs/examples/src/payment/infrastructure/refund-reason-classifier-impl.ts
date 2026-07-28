import { Injectable, Logger } from '@nestjs/common'

import { getLlmModel, getOllamaBaseUrl } from '@/config/llm.config'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { RefundReasonCategory } from '@/payment/domain/refund'

const CATEGORIES: RefundReasonCategory[] = ['DEFECTIVE_PRODUCT', 'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'LATE_DELIVERY', 'DUPLICATE_CHARGE', 'OTHER']

// A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
// technical-infrastructure concern, not a domain error — this is a best-effort ops-analytics
// enrichment, so it degrades to OTHER rather than ever blocking or retrying indefinitely.
const FALLBACK_CATEGORY: RefundReasonCategory = 'OTHER'

const RESPONSE_FORMAT = {
  type: 'object',
  properties: {
    category: { type: 'string', enum: CATEGORIES }
  },
  required: ['category'],
  additionalProperties: false
}

interface OllamaChatResponse {
  message?: { content?: string }
}

function buildSystemPrompt(): string {
  return 'You classify a customer\'s stated refund reason into exactly one category, for internal reporting only '
    + `(this never affects whether the refund is approved). Categories: ${CATEGORIES.join(', ')}. Use OTHER only `
    + 'when none of the other categories plausibly fit. Respond only through the given schema.'
}

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call — the same self-hosted qwen2.5:1.5b Ollama setup as Account BC's
// TransactionAutoCategorizerImpl, just a different prompt/schema for a different job.
@Injectable()
export class RefundReasonClassifierImpl extends RefundReasonClassifier {
  private readonly logger = new Logger(RefundReasonClassifierImpl.name)

  public async classify(reason: string): Promise<RefundReasonCategory> {
    try {
      const response = await fetch(`${getOllamaBaseUrl()}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: getLlmModel(),
          stream: false,
          messages: [
            { role: 'system', content: buildSystemPrompt() },
            { role: 'user', content: reason }
          ],
          format: RESPONSE_FORMAT
        })
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Refund reason classification failed, using fallback category', status: response.status })
        return FALLBACK_CATEGORY
      }

      const body = await response.json() as OllamaChatResponse
      const content = body.message?.content
      if (!content) return FALLBACK_CATEGORY

      const parsed = JSON.parse(content) as { category?: string }
      return CATEGORIES.includes(parsed.category as RefundReasonCategory) ? (parsed.category as RefundReasonCategory) : FALLBACK_CATEGORY
    } catch (error) {
      this.logger.warn({ message: 'Refund reason classification failed, using fallback category', error: (error as Error).message })
      return FALLBACK_CATEGORY
    }
  }
}
