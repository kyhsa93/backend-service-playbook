import { Test } from '@nestjs/testing'

import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { SendCardStatementsCommand } from '@/payment/application/command/send-card-statements-command'
import { SendCardStatementsCommandHandler } from '@/payment/application/command/send-card-statements-command-handler'
import { CardStatementNotificationService } from '@/payment/application/service/card-statement-notification-service'
import { PaymentRepository } from '@/payment/domain/payment-repository'

describe('SendCardStatementsCommandHandler', () => {
  let handler: SendCardStatementsCommandHandler
  let cardAdapter: jest.Mocked<CardAdapter>
  let accountAdapter: jest.Mocked<AccountAdapter>
  let paymentRepository: jest.Mocked<PaymentRepository>
  let notificationService: jest.Mocked<CardStatementNotificationService>

  const command = new SendCardStatementsCommand({
    statementMonth: '2026-06',
    monthStart: new Date('2026-06-01T00:00:00.000Z'),
    monthEnd: new Date('2026-07-01T00:00:00.000Z')
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        SendCardStatementsCommandHandler,
        { provide: CardAdapter, useValue: { findCard: jest.fn(), findActiveCards: jest.fn() } },
        { provide: AccountAdapter, useValue: { findAccount: jest.fn() } },
        { provide: PaymentRepository, useValue: { findPayments: jest.fn(), savePayment: jest.fn(), summarizePayments: jest.fn() } },
        {
          provide: CardStatementNotificationService,
          useValue: { hasSentStatement: jest.fn(), sendStatement: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(SendCardStatementsCommandHandler)
    cardAdapter = module.get(CardAdapter)
    accountAdapter = module.get(AccountAdapter)
    paymentRepository = module.get(PaymentRepository)
    notificationService = module.get(CardStatementNotificationService)
  })

  it('execute_when_an_ACTIVE_card_has_not_been_sent_yet_then_sends_with_summary_stats_and_returns_the_count', async () => {
    cardAdapter.findActiveCards.mockResolvedValue([{ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1' }])
    notificationService.hasSentStatement.mockResolvedValue(false)
    paymentRepository.summarizePayments.mockResolvedValue({ count: 3, totalAmount: 15000 })
    accountAdapter.findAccount.mockResolvedValue({
      accountId: 'account-1', active: true, balanceAmount: 100000, currency: 'KRW', email: 'owner1@example.com'
    })

    const sentCount = await handler.execute(command)

    expect(sentCount).toBe(1)
    expect(notificationService.sendStatement).toHaveBeenCalledWith({
      cardId: 'card-1',
      accountId: 'account-1',
      statementMonth: '2026-06',
      paymentCount: 3,
      totalAmount: 15000,
      currency: 'KRW',
      recipient: 'owner1@example.com'
    })
  })

  it('execute_when_the_usage_count_is_0_then_still_sends', async () => {
    cardAdapter.findActiveCards.mockResolvedValue([{ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1' }])
    notificationService.hasSentStatement.mockResolvedValue(false)
    paymentRepository.summarizePayments.mockResolvedValue({ count: 0, totalAmount: 0 })
    accountAdapter.findAccount.mockResolvedValue({
      accountId: 'account-1', active: true, balanceAmount: 100000, currency: 'KRW', email: 'owner1@example.com'
    })

    const sentCount = await handler.execute(command)

    expect(sentCount).toBe(1)
    expect(notificationService.sendStatement).toHaveBeenCalledWith(expect.objectContaining({ paymentCount: 0, totalAmount: 0 }))
  })

  it('execute_when_already_sent_this_month_then_skips_and_does_not_call_the_summary_query_either', async () => {
    cardAdapter.findActiveCards.mockResolvedValue([{ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1' }])
    notificationService.hasSentStatement.mockResolvedValue(true)

    const sentCount = await handler.execute(command)

    expect(sentCount).toBe(0)
    expect(paymentRepository.summarizePayments).not.toHaveBeenCalled()
    expect(notificationService.sendStatement).not.toHaveBeenCalled()
  })

  it('execute_when_the_account_cannot_be_found_then_skips', async () => {
    cardAdapter.findActiveCards.mockResolvedValue([{ cardId: 'card-1', accountId: 'account-1', ownerId: 'owner-1' }])
    notificationService.hasSentStatement.mockResolvedValue(false)
    paymentRepository.summarizePayments.mockResolvedValue({ count: 1, totalAmount: 1000 })
    accountAdapter.findAccount.mockResolvedValue(null)

    const sentCount = await handler.execute(command)

    expect(sentCount).toBe(0)
    expect(notificationService.sendStatement).not.toHaveBeenCalled()
  })

  it('execute_when_there_are_no_ACTIVE_cards_then_returns_0', async () => {
    cardAdapter.findActiveCards.mockResolvedValue([])

    const sentCount = await handler.execute(command)

    expect(sentCount).toBe(0)
  })
})
