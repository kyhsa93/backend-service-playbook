import { Test } from '@nestjs/testing'

import { CreatePaymentCommandHandler } from '@/payment/application/command/create-payment-command-handler'
import { CreatePaymentCommand } from '@/payment/application/command/create-payment-command'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { TransactionManager } from '@/database/transaction-manager'

describe('CreatePaymentCommandHandler', () => {
  let handler: CreatePaymentCommandHandler
  let paymentRepository: jest.Mocked<PaymentRepository>
  let cardAdapter: jest.Mocked<CardAdapter>
  let accountAdapter: jest.Mocked<AccountAdapter>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        CreatePaymentCommandHandler,
        { provide: PaymentRepository, useValue: { findPayments: jest.fn(), savePayment: jest.fn() } },
        { provide: CardAdapter, useValue: { findCard: jest.fn() } },
        { provide: AccountAdapter, useValue: { findAccount: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(CreatePaymentCommandHandler)
    paymentRepository = module.get(PaymentRepository)
    cardAdapter = module.get(CardAdapter)
    accountAdapter = module.get(AccountAdapter)
  })

  const command = (): CreatePaymentCommand => new CreatePaymentCommand({ cardId: 'card-1', amount: 5000, requesterId: 'owner-1' })

  it('execute_when_card_and_account_are_active_and_balance_is_sufficient_then_creates_the_payment_and_saves_it_as_COMPLETED', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: true, balanceAmount: 10000, currency: 'KRW', email: 'owner1@example.com' })

    const payment = await handler.execute(command())

    expect(cardAdapter.findCard).toHaveBeenCalledWith({ cardId: 'card-1', ownerId: 'owner-1' })
    expect(accountAdapter.findAccount).toHaveBeenCalledWith({ accountId: 'account-1', ownerId: 'owner-1' })
    expect(payment.status).toBe(PaymentStatus.COMPLETED)
    expect(payment.accountId).toBe('account-1')
    expect(paymentRepository.savePayment).toHaveBeenCalledWith(payment)
  })

  it('execute_when_the_card_does_not_exist_then_throws', async () => {
    cardAdapter.findCard.mockResolvedValue(null)

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['The card to link could not be found.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_the_card_is_inactive_then_throws', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: false })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['Only an active card can be used for payment.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_the_account_is_inactive_then_throws', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: false, balanceAmount: 10000, currency: 'KRW', email: 'owner1@example.com' })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['Only an active account can be used for payment.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_the_balance_is_insufficient_then_throws', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: true, balanceAmount: 1000, currency: 'KRW', email: 'owner1@example.com' })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['Payment cannot be made due to insufficient account balance.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })
})
