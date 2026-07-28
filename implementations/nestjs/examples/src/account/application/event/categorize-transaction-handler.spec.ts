import { Test } from '@nestjs/testing'

import { CategorizeTransactionHandler } from '@/account/application/event/categorize-transaction-handler'
import { TransactionAutoCategorizer } from '@/account/application/service/transaction-auto-categorizer'
import { Money } from '@/account/domain/money'
import { Transaction } from '@/account/domain/transaction'
import { TransactionRepository } from '@/account/domain/transaction-repository'

describe('CategorizeTransactionHandler', () => {
  let handler: CategorizeTransactionHandler
  let transactionAutoCategorizer: jest.Mocked<TransactionAutoCategorizer>
  let transactionRepository: jest.Mocked<TransactionRepository>

  const transaction = new Transaction({
    transactionId: 'transaction-1', accountId: 'account-1', type: 'WITHDRAWAL',
    amount: new Money({ amount: 5500, currency: 'KRW' }), merchantName: 'Starbucks Gangnam'
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        CategorizeTransactionHandler,
        { provide: TransactionAutoCategorizer, useValue: { categorize: jest.fn() } },
        { provide: TransactionRepository, useValue: { findTransaction: jest.fn(), saveTransaction: jest.fn() } }
      ]
    }).compile()

    handler = module.get(CategorizeTransactionHandler)
    transactionAutoCategorizer = module.get(TransactionAutoCategorizer)
    transactionRepository = module.get(TransactionRepository)
  })

  it('handle_when_the_event_has_a_merchantName_then_categorizes_and_saves_it', async () => {
    transactionRepository.findTransaction.mockResolvedValue(transaction)
    transactionAutoCategorizer.categorize.mockResolvedValue('FOOD')

    await handler.handle({
      transactionId: 'transaction-1',
      amount: { amount: 5500, currency: 'KRW' },
      merchantName: 'Starbucks Gangnam'
    })

    expect(transactionAutoCategorizer.categorize).toHaveBeenCalledWith({ merchantName: 'Starbucks Gangnam', amount: 5500 })
    expect(transactionRepository.saveTransaction).toHaveBeenCalledWith(
      expect.objectContaining({ transactionId: 'transaction-1', category: 'FOOD' })
    )
  })

  it('handle_when_the_event_has_no_merchantName_then_skips_categorization_entirely', async () => {
    await handler.handle({ transactionId: 'transaction-1', amount: { amount: 5500, currency: 'KRW' } })

    expect(transactionRepository.findTransaction).not.toHaveBeenCalled()
    expect(transactionAutoCategorizer.categorize).not.toHaveBeenCalled()
    expect(transactionRepository.saveTransaction).not.toHaveBeenCalled()
  })

  it('handle_when_the_transaction_no_longer_exists_then_skips_categorization_without_throwing', async () => {
    transactionRepository.findTransaction.mockResolvedValue(null)

    await handler.handle({
      transactionId: 'transaction-1',
      amount: { amount: 5500, currency: 'KRW' },
      merchantName: 'Starbucks Gangnam'
    })

    expect(transactionAutoCategorizer.categorize).not.toHaveBeenCalled()
    expect(transactionRepository.saveTransaction).not.toHaveBeenCalled()
  })
})
