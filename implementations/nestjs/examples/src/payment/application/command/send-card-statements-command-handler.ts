import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { SendCardStatementsCommand } from '@/payment/application/command/send-card-statements-command'
import { CardStatementNotificationService } from '@/payment/application/service/card-statement-notification-service'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentStatus } from '@/payment/payment-enum'

// The Command the payment.send-card-statements Task Controller delegates to.
//
// Why this use case lives in Payment BC: even though it's "card usage history," the source
// data (payment count·total amount) is owned by the Payment Aggregate. Since Card BC is
// already something Payment BC depends on (PaymentModule imports CardModule), adding a query
// Adapter in the opposite direction (Card → Payment) would create a circular dependency.
// Since Payment can already synchronously query both BCs via CardAdapter/AccountAdapter (ACL),
// coordinating it here is the only batch possible without a cycle (see the imports direction
// in card/card-module.ts, payment/payment-module.ts).
//
// Idempotency is close to Level 1 — right before sendStatement(), hasSentStatement() checks
// whether the same (cardId, statementMonth) combination already exists and skips if so. The
// last line of defense is the (cardId, statementMonth) unique constraint on sent_card_statement.
@CommandHandler(SendCardStatementsCommand)
export class SendCardStatementsCommandHandler implements ICommandHandler<SendCardStatementsCommand, number> {
  constructor(
    private readonly cardAdapter: CardAdapter,
    private readonly accountAdapter: AccountAdapter,
    private readonly paymentRepository: PaymentRepository,
    private readonly notificationService: CardStatementNotificationService
  ) {}

  public async execute(command: SendCardStatementsCommand): Promise<number> {
    const activeCards = await this.cardAdapter.findActiveCards()
    let sentCount = 0

    for (const card of activeCards) {
      const alreadySent = await this.notificationService.hasSentStatement(card.cardId, command.statementMonth)
      if (alreadySent) continue

      const summary = await this.paymentRepository.summarizePayments({
        cardId: card.cardId,
        status: [PaymentStatus.COMPLETED],
        createdAtFrom: command.monthStart,
        createdAtTo: command.monthEnd
      })

      // If the account can't be found (a defensive case, e.g. already deleted/transferred), there's no recipient, so skip it.
      const account = await this.accountAdapter.findAccount({ accountId: card.accountId, ownerId: card.ownerId })
      if (!account) continue

      // Just like a real card statement, it's sent even when the usage count is 0 — unlike
      // interest payment, "this month's usage history" is meaningful information precisely because there was no activity.
      await this.notificationService.sendStatement({
        cardId: card.cardId,
        accountId: card.accountId,
        statementMonth: command.statementMonth,
        paymentCount: summary.count,
        totalAmount: summary.totalAmount,
        currency: account.currency,
        recipient: account.email
      })
      sentCount++
    }

    return sentCount
  }
}
