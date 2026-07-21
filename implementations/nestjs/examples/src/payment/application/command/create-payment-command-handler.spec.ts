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

  it('execute_when_카드_활성_계좌_잔액_충분_then_결제를_생성하고_COMPLETED로_저장한다', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: true, balanceAmount: 10000, currency: 'KRW', email: 'owner1@example.com' })

    const payment = await handler.execute(command())

    expect(cardAdapter.findCard).toHaveBeenCalledWith({ cardId: 'card-1', ownerId: 'owner-1' })
    expect(accountAdapter.findAccount).toHaveBeenCalledWith({ accountId: 'account-1', ownerId: 'owner-1' })
    expect(payment.status).toBe(PaymentStatus.COMPLETED)
    expect(payment.accountId).toBe('account-1')
    expect(paymentRepository.savePayment).toHaveBeenCalledWith(payment)
  })

  it('execute_when_카드가_없으면_then_에러를_throw한다', async () => {
    cardAdapter.findCard.mockResolvedValue(null)

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['연결할 카드를 찾을 수 없습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_카드가_비활성이면_then_에러를_throw한다', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: false })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['활성 상태의 카드로만 결제할 수 있습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_계좌가_비활성이면_then_에러를_throw한다', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: false, balanceAmount: 10000, currency: 'KRW', email: 'owner1@example.com' })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['활성 상태의 계좌로만 결제할 수 있습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })

  it('execute_when_잔액이_부족하면_then_에러를_throw한다', async () => {
    cardAdapter.findCard.mockResolvedValue({ cardId: 'card-1', accountId: 'account-1', active: true })
    accountAdapter.findAccount.mockResolvedValue({ accountId: 'account-1', active: true, balanceAmount: 1000, currency: 'KRW', email: 'owner1@example.com' })

    await expect(handler.execute(command())).rejects.toThrow(PaymentErrorMessage['계좌 잔액이 부족하여 결제할 수 없습니다.'])
    expect(paymentRepository.savePayment).not.toHaveBeenCalled()
  })
})
