import { Injectable, Logger } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { HandleIntegrationEvent } from '@/outbox/event-handler-registry'
import { DepositByPaymentCommand } from '@/account/application/command/deposit-by-payment-command'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'

// An Interface input adapter that receives Integration Events published by an external BC
// (Payment). The same placement and role as Card BC's card-integration-event-controller.ts
// (which subscribes to Account's events) — just as Account never queries Payment via an
// Adapter, Payment never references Account directly either. It calls only its own domain's
// use case (Command), and throws exceptions as-is so the OutboxConsumer doesn't delete the
// message and instead handles the retry.
@Injectable()
export class AccountIntegrationEventController {
  private readonly logger = new Logger(AccountIntegrationEventController.name)

  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('payment.completed.v1')
  public async onPaymentCompleted(event: { paymentId: string; accountId: string; amount: number }): Promise<void> {
    this.logger.log({ message: 'payment.completed.v1 received', payment_id: event.paymentId, account_id: event.accountId })
    await this.commandBus.execute(new WithdrawByPaymentCommand({
      accountId: event.accountId,
      amount: event.amount,
      referenceId: event.paymentId
    }))
  }

  @HandleIntegrationEvent('payment.cancelled.v1')
  public async onPaymentCancelled(event: { paymentId: string; accountId: string; amount: number }): Promise<void> {
    this.logger.log({ message: 'payment.cancelled.v1 received', payment_id: event.paymentId, account_id: event.accountId })
    await this.commandBus.execute(new DepositByPaymentCommand({
      accountId: event.accountId,
      amount: event.amount,
      referenceId: event.paymentId
    }))
  }

  @HandleIntegrationEvent('refund.approved.v1')
  public async onRefundApproved(event: {
    refundId: string
    paymentId: string
    accountId: string
    amount: number
  }): Promise<void> {
    this.logger.log({ message: 'refund.approved.v1 received', refund_id: event.refundId, account_id: event.accountId })
    await this.commandBus.execute(new DepositByPaymentCommand({
      accountId: event.accountId,
      amount: event.amount,
      referenceId: event.refundId
    }))
  }
}
