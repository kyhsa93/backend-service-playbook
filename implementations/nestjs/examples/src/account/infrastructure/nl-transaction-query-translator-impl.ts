import { Injectable, Logger } from '@nestjs/common'

import { getLlmModel, getOllamaBaseUrl } from '@/config/llm.config'
import { NlTransactionQueryTranslator, TransactionFilter } from '@/account/application/service/nl-transaction-query-translator'
import { TransactionType } from '@/account/domain/transaction'

const TYPES: TransactionType[] = ['DEPOSIT', 'WITHDRAWAL', 'INTEREST']

// An empty filter degrades gracefully to "no narrowing" — the Query Handler still runs
// AccountQuery.getTransactions with no type/date constraint, so a translation failure never
// blocks the question from being answered against the requester's most recent transactions.
const FALLBACK_FILTER: TransactionFilter = {}

const RESPONSE_FORMAT = {
  type: 'object',
  properties: {
    type: { type: 'string', enum: [...TYPES, 'ANY'] },
    fromDate: { type: 'string' },
    toDate: { type: 'string' }
  },
  required: ['type', 'fromDate', 'toDate'],
  additionalProperties: false
}

interface OllamaChatResponse {
  message?: { content?: string }
}

function buildSystemPrompt(): string {
  const today = new Date().toISOString().slice(0, 10)
  return 'You translate a user\'s natural-language question about their own bank account transaction history into '
    + `a structured JSON filter. Today's date is ${today}. Resolve any relative date expression ("this month", `
    + '"last week") against that date.\n'
    + 'Fields: "type" — DEPOSIT, WITHDRAWAL, INTEREST, or ANY if the question doesn\'t ask about a specific type. '
    + '"fromDate"/"toDate" — an ISO 8601 date (YYYY-MM-DD), or an empty string if the question implies no date '
    + 'boundary on that side.\n'
    + 'Only extract constraints the question actually states or clearly implies. Never invent a date range or '
    + 'transaction type the question doesn\'t support. Respond only through the given schema.'
}

// A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
// call — the "Retrieve"-preparation half of a structured-data RAG pipeline (NlTransactionAnswerComposer
// is the "Generate" half). Talks to Ollama's native /api/chat endpoint over plain HTTP, the same
// self-hosted qwen2.5:1.5b setup used elsewhere in this repo for LLM Technical Services.
@Injectable()
export class NlTransactionQueryTranslatorImpl extends NlTransactionQueryTranslator {
  private readonly logger = new Logger(NlTransactionQueryTranslatorImpl.name)

  public async translate(question: string): Promise<TransactionFilter> {
    try {
      const response = await fetch(`${getOllamaBaseUrl()}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: getLlmModel(),
          stream: false,
          messages: [
            { role: 'system', content: buildSystemPrompt() },
            { role: 'user', content: question }
          ],
          format: RESPONSE_FORMAT
        })
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Transaction query translation failed, using no filter', status: response.status })
        return FALLBACK_FILTER
      }

      const body = await response.json() as OllamaChatResponse
      const content = body.message?.content
      if (!content) return FALLBACK_FILTER

      const parsed = JSON.parse(content) as { type?: string; fromDate?: string; toDate?: string }
      return {
        type: TYPES.includes(parsed.type as TransactionType) ? (parsed.type as TransactionType) : undefined,
        fromDate: isValidIsoDate(parsed.fromDate) ? parsed.fromDate : undefined,
        toDate: isValidIsoDate(parsed.toDate) ? parsed.toDate : undefined
      }
    } catch (error) {
      // A translation failure is a technical-infrastructure concern, not a domain error — it
      // must never block the question from being answered. Swallow it here at the boundary.
      this.logger.warn({ message: 'Transaction query translation failed, using no filter', error: (error as Error).message })
      return FALLBACK_FILTER
    }
  }
}

function isValidIsoDate(value?: string): value is string {
  return !!value && /^\d{4}-\d{2}-\d{2}$/.test(value) && !isNaN(new Date(value).getTime())
}
