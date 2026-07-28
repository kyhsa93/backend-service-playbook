import { Injectable, Logger } from '@nestjs/common'

import { getLlmModel, getOllamaBaseUrl } from '@/config/llm.config'
import { TransactionAutoCategorizer } from '@/account/application/service/transaction-auto-categorizer'
import { TransactionCategory } from '@/account/domain/transaction'

const CATEGORIES: TransactionCategory[] = ['FOOD', 'TRANSPORT', 'SHOPPING', 'HOUSING', 'MEDICAL', 'ENTERTAINMENT', 'UTILITIES', 'OTHER']

// A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
// technical-infrastructure concern, not a domain error — this is a best-effort enrichment, not
// a financial correctness concern, so it degrades to OTHER rather than ever blocking or retrying
// indefinitely. The same posture as NlTransactionQueryTranslatorImpl falling back to no filter.
const FALLBACK_CATEGORY: TransactionCategory = 'OTHER'

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
  return 'You classify a bank withdrawal into exactly one spending category based on its payee/merchant name and '
    + `amount. Categories: ${CATEGORIES.join(', ')}. Use OTHER only when none of the other categories plausibly `
    + 'fit. Respond only through the given schema.'
}

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call — the same self-hosted qwen2.5:1.5b Ollama setup as NlTransactionQueryTranslatorImpl/
// NlTransactionAnswerComposerImpl, just a different prompt/schema for a different job
// (classification instead of query translation or answer generation).
@Injectable()
export class TransactionAutoCategorizerImpl extends TransactionAutoCategorizer {
  private readonly logger = new Logger(TransactionAutoCategorizerImpl.name)

  public async categorize(params: { merchantName: string; amount: number }): Promise<TransactionCategory> {
    try {
      const response = await fetch(`${getOllamaBaseUrl()}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: getLlmModel(),
          stream: false,
          messages: [
            { role: 'system', content: buildSystemPrompt() },
            { role: 'user', content: `Merchant: ${params.merchantName}\nAmount: ${params.amount}` }
          ],
          format: RESPONSE_FORMAT
        })
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Transaction categorization failed, using fallback category', status: response.status })
        return FALLBACK_CATEGORY
      }

      const body = await response.json() as OllamaChatResponse
      const content = body.message?.content
      if (!content) return FALLBACK_CATEGORY

      const parsed = JSON.parse(content) as { category?: string }
      return CATEGORIES.includes(parsed.category as TransactionCategory) ? (parsed.category as TransactionCategory) : FALLBACK_CATEGORY
    } catch (error) {
      this.logger.warn({ message: 'Transaction categorization failed, using fallback category', error: (error as Error).message })
      return FALLBACK_CATEGORY
    }
  }
}
