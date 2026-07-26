import { TransactionSummaryResult } from '@/account/application/query/account-result'
import { NlTransactionAnswerComposerImpl } from '@/account/infrastructure/nl-transaction-answer-composer-impl'

describe('NlTransactionAnswerComposerImpl', () => {
  let composer: NlTransactionAnswerComposerImpl
  let fetchMock: jest.Mock

  const transactions: TransactionSummaryResult[] = [
    { transactionId: 't1', type: 'DEPOSIT', amount: { amount: 1000, currency: 'KRW' }, createdAt: new Date('2026-07-10') }
  ]

  beforeEach(() => {
    composer = new NlTransactionAnswerComposerImpl()
    fetchMock = jest.fn()
    global.fetch = fetchMock as never
  })

  it('compose_when_the_model_answers_then_returns_the_trimmed_answer', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ message: { content: '  You deposited 1000 KRW.  ' } }) })

    const answer = await composer.compose('How much did I deposit?', transactions)

    expect(answer).toBe('You deposited 1000 KRW.')
  })

  it('compose_when_the_ollama_call_fails_then_falls_back_to_a_plain_summary_naming_the_actual_count', async () => {
    fetchMock.mockRejectedValue(new Error('connection refused'))

    const answer = await composer.compose('How much did I deposit?', transactions)

    expect(answer).toContain('Found 1 matching transaction(s)')
  })

  it('compose_when_there_are_no_transactions_and_the_call_fails_then_says_so_plainly', async () => {
    fetchMock.mockRejectedValue(new Error('connection refused'))

    const answer = await composer.compose('How much did I withdraw?', [])

    expect(answer).toBe('No matching transactions were found.')
  })
})
