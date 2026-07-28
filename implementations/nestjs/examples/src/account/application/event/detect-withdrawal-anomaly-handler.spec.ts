import { Test } from '@nestjs/testing'

import { DetectWithdrawalAnomalyHandler } from '@/account/application/event/detect-withdrawal-anomaly-handler'
import { NotificationService } from '@/account/application/service/notification-service'
import { AccountRepository } from '@/account/domain/account-repository'

describe('DetectWithdrawalAnomalyHandler', () => {
  let handler: DetectWithdrawalAnomalyHandler
  let accountRepository: jest.Mocked<AccountRepository>
  let notificationService: jest.Mocked<NotificationService>

  const baseEvent = {
    accountId: 'account-1',
    email: 'owner@example.com',
    transactionId: 'transaction-1',
    amount: { amount: 5000000, currency: 'KRW' }
  }

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        DetectWithdrawalAnomalyHandler,
        { provide: AccountRepository, useValue: { findRecentWithdrawalAmounts: jest.fn(), findAccounts: jest.fn(), saveAccount: jest.fn(), summarizeTransactions: jest.fn(), hasTransactionWithReference: jest.fn(), findRecentAnalyses: jest.fn() } },
        { provide: NotificationService, useValue: { sendEmail: jest.fn() } }
      ]
    }).compile()

    handler = module.get(DetectWithdrawalAnomalyHandler)
    accountRepository = module.get(AccountRepository)
    notificationService = module.get(NotificationService)
  })

  it('handle_when_the_amount_is_a_statistical_outlier_against_the_accounts_history_then_sends_an_alert_email', async () => {
    accountRepository.findRecentWithdrawalAmounts.mockResolvedValue([10000, 12000, 9000, 11000, 10500, 9500])

    await handler.handle(baseEvent)

    expect(accountRepository.findRecentWithdrawalAmounts).toHaveBeenCalledWith('account-1', 'transaction-1', 30)
    expect(notificationService.sendEmail).toHaveBeenCalledWith(expect.objectContaining({
      accountId: 'account-1',
      eventType: 'WithdrawalAnomalyDetected',
      recipient: 'owner@example.com'
    }))
  })

  it('handle_when_the_amount_is_within_the_accounts_normal_range_then_sends_no_alert', async () => {
    accountRepository.findRecentWithdrawalAmounts.mockResolvedValue([4900000, 5100000, 4950000, 5050000, 5000000])

    await handler.handle(baseEvent)

    expect(notificationService.sendEmail).not.toHaveBeenCalled()
  })

  it('handle_when_the_account_has_fewer_than_5_prior_withdrawals_then_sends_no_alert_regardless_of_amount', async () => {
    accountRepository.findRecentWithdrawalAmounts.mockResolvedValue([10000, 12000])

    await handler.handle(baseEvent)

    expect(notificationService.sendEmail).not.toHaveBeenCalled()
  })
})
