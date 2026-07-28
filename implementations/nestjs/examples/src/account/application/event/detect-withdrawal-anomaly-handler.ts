import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'
import { NotificationService } from '@/account/application/service/notification-service'
import { AccountRepository } from '@/account/domain/account-repository'
import { AnomalyDetectionService } from '@/account/domain/anomaly-detection-service'

// How many of the account's own most recent (excluding this one) withdrawals
// AnomalyDetectionService trains its mean/stddev against.
const HISTORY_WINDOW = 30

// Reacts to MoneyWithdrawn (registered in account-module.ts alongside MoneyWithdrawnHandler and
// CategorizeTransactionHandler — EventHandlerRegistry supports multiple subscribers per event
// type) to flag a withdrawal that's a statistical outlier against the account's own history.
// Deliberately only ever sends a Notification — it never blocks, reverses, or judges the
// withdrawal itself (the withdrawal already completed before this even runs). This is the
// design constraint that keeps it out of the domain-purity trap the earlier
// RefundFraudRiskScorer/RefundReasonClassifier fell into (both removed — see root
// docs/architecture/domain-service.md's Domain Service section): a signal that only ever
// informs a human, never one a Domain Service treats as a judgment input.
@Injectable()
export class DetectWithdrawalAnomalyHandler {
  private readonly logger = new Logger(DetectWithdrawalAnomalyHandler.name)
  private readonly anomalyDetectionService = new AnomalyDetectionService()

  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly notificationService: NotificationService
  ) {}

  @HandleEvent('MoneyWithdrawn')
  public async handle(event: {
    accountId: string
    email: string
    transactionId: string
    amount: { amount: number; currency: string }
  }): Promise<void> {
    const history = await this.accountRepository.findRecentWithdrawalAmounts(event.accountId, event.transactionId, HISTORY_WINDOW)
    const isAnomalous = this.anomalyDetectionService.isAnomalous(history, event.amount.amount)
    if (!isAnomalous) return

    await this.notificationService.sendEmail({
      accountId: event.accountId,
      eventType: 'WithdrawalAnomalyDetected',
      recipient: event.email,
      subject: '[Account] Unusual withdrawal detected',
      body: `A withdrawal of ${event.amount.amount} ${event.amount.currency} is unusually large compared to your recent activity. `
        + 'If this wasn\'t you, please contact support immediately.'
    })
    this.logger.log({
      message: 'Anomalous withdrawal detected, alert sent',
      account_id: event.accountId,
      transaction_id: event.transactionId,
      amount: event.amount.amount
    })
  }
}
