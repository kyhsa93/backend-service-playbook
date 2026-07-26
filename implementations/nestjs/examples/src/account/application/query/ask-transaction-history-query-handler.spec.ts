import { Test } from '@nestjs/testing'

import { AccountQuery } from '@/account/application/query/account-query'
import { AskTransactionHistoryQuery } from '@/account/application/query/ask-transaction-history-query'
import { AskTransactionHistoryQueryHandler } from '@/account/application/query/ask-transaction-history-query-handler'
import { NlTransactionAnswerComposer } from '@/account/application/service/nl-transaction-answer-composer'
import { NlTransactionQueryTranslator } from '@/account/application/service/nl-transaction-query-translator'

describe('AskTransactionHistoryQueryHandler', () => {
  let handler: AskTransactionHistoryQueryHandler
  let accountQuery: jest.Mocked<AccountQuery>
  let translator: jest.Mocked<NlTransactionQueryTranslator>
  let composer: jest.Mocked<NlTransactionAnswerComposer>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        AskTransactionHistoryQueryHandler,
        { provide: AccountQuery, useValue: { getAccount: jest.fn(), getTransactions: jest.fn() } },
        { provide: NlTransactionQueryTranslator, useValue: { translate: jest.fn() } },
        { provide: NlTransactionAnswerComposer, useValue: { compose: jest.fn() } }
      ]
    }).compile()

    handler = module.get(AskTransactionHistoryQueryHandler)
    accountQuery = module.get(AccountQuery)
    translator = module.get(NlTransactionQueryTranslator)
    composer = module.get(NlTransactionAnswerComposer)
  })

  it('execute_when_called_then_scopes_the_lookup_to_the_requester_never_to_a_value_from_the_translated_filter', async () => {
    translator.translate.mockResolvedValue({ type: 'WITHDRAWAL', fromDate: '2026-07-01', toDate: '2026-07-31' })
    accountQuery.getTransactions.mockResolvedValue({ transactions: [], count: 0 })
    composer.compose.mockResolvedValue('No matching transactions were found.')

    await handler.execute(new AskTransactionHistoryQuery({
      accountId: 'account-1',
      requesterId: 'owner-1',
      question: 'How much did I withdraw in July?'
    }))

    // ownerId always comes from the authenticated requester, never from the translated filter —
    // TransactionFilter has no ownerId field to begin with, but this pins the intent explicitly.
    expect(accountQuery.getTransactions).toHaveBeenCalledWith({
      accountId: 'account-1',
      ownerId: 'owner-1',
      type: 'WITHDRAWAL',
      fromDate: '2026-07-01',
      toDate: '2026-07-31',
      take: 50,
      page: 0
    })
  })

  it('execute_when_called_then_composes_the_answer_from_the_retrieved_transactions_and_returns_the_match_count', async () => {
    const transactions = [{ transactionId: 't1', type: 'DEPOSIT', amount: { amount: 1000, currency: 'KRW' }, createdAt: new Date() }]
    translator.translate.mockResolvedValue({})
    accountQuery.getTransactions.mockResolvedValue({ transactions: transactions as never, count: 1 })
    composer.compose.mockResolvedValue('You deposited 1000 KRW.')

    const result = await handler.execute(new AskTransactionHistoryQuery({
      accountId: 'account-1',
      requesterId: 'owner-1',
      question: 'How much did I deposit?'
    }))

    expect(composer.compose).toHaveBeenCalledWith('How much did I deposit?', transactions)
    expect(result).toEqual({ answer: 'You deposited 1000 KRW.', matchedCount: 1 })
  })
})
