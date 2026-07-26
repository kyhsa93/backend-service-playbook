import { Injectable, Logger } from '@nestjs/common'

import { getLlmModel, getOllamaBaseUrl } from '@/config/llm.config'
import { TransactionSummaryResult } from '@/account/application/query/account-result'
import { NlTransactionAnswerComposer } from '@/account/application/service/nl-transaction-answer-composer'

// Live-tested against a Korean question ("이번 달에 얼마 입금했어?"): the retrieval/filtering and the
// factual content of the answer were correct, but qwen2.5:1.5b answered in English despite the
// explicit language-matching instruction below — a known limitation of this small model, not of
// the pipeline. A larger model would likely follow it more reliably; left as English-leaning
// behavior rather than adding translation post-processing, which is out of scope for this example.
const SYSTEM_PROMPT = 'You answer a user\'s question about their own bank account transactions using ONLY the '
  + 'transaction data listed below — never mention or infer a transaction that isn\'t in that list. Concisely '
  + '(2-3 sentences). If the listed data doesn\'t contain enough information to answer (e.g. it\'s empty), say so '
  + 'plainly instead of guessing.\n'
  + 'IMPORTANT: detect the language the question itself is written in, and write your entire answer in that same '
  + 'language — e.g. a Korean question always gets a Korean answer, an English question always gets an English '
  + 'answer, regardless of what language this instruction or the transaction data is in.'

interface OllamaChatResponse {
  message?: { content?: string }
}

function formatTransactions(transactions: TransactionSummaryResult[]): string {
  if (transactions.length === 0) return '(no matching transactions)'
  return transactions
    .map((t) => `- ${t.type} ${t.amount.amount} ${t.amount.currency} on ${t.createdAt.toString().slice(0, 10)}`)
    .join('\n')
}

// A plain, non-blocking fallback used whenever the LLM call fails — describes the same data a
// working call would have been grounded in, just without natural-language phrasing.
function fallbackAnswer(transactions: TransactionSummaryResult[]): string {
  if (transactions.length === 0) return 'No matching transactions were found.'
  return `Found ${transactions.length} matching transaction(s):\n${formatTransactions(transactions)}`
}

// A Technical Service (see root docs/architecture/domain-service.md) generating a natural-
// language answer from already-retrieved transaction records — the "Generate" half of a
// structured-data RAG pipeline (NlTransactionQueryTranslator is the "Retrieve"-preparation
// half). Uses the same self-hosted Ollama setup, without a JSON-schema-constrained response
// since the output here is free-form prose, not a structured value the caller parses.
@Injectable()
export class NlTransactionAnswerComposerImpl extends NlTransactionAnswerComposer {
  private readonly logger = new Logger(NlTransactionAnswerComposerImpl.name)

  public async compose(question: string, transactions: TransactionSummaryResult[]): Promise<string> {
    try {
      const response = await fetch(`${getOllamaBaseUrl()}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: getLlmModel(),
          stream: false,
          messages: [
            { role: 'system', content: SYSTEM_PROMPT },
            { role: 'user', content: `Question: ${question}\n\nTransactions:\n${formatTransactions(transactions)}` }
          ]
        })
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Answer composition failed, using fallback', status: response.status })
        return fallbackAnswer(transactions)
      }

      const body = await response.json() as OllamaChatResponse
      const content = body.message?.content
      return content && content.trim().length > 0 ? content.trim() : fallbackAnswer(transactions)
    } catch (error) {
      // A composition failure is a technical-infrastructure concern, not a domain error — it
      // must never block the question from getting *an* answer, even a plain one.
      this.logger.warn({ message: 'Answer composition failed, using fallback', error: (error as Error).message })
      return fallbackAnswer(transactions)
    }
  }
}
