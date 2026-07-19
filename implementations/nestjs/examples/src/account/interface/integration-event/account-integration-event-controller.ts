import { Injectable, Logger } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { HandleIntegrationEvent } from '@/outbox/event-handler-registry'
import { DepositByPaymentCommand } from '@/account/application/command/deposit-by-payment-command'
import { WithdrawByPaymentCommand } from '@/account/application/command/withdraw-by-payment-command'

// 외부 BC(Payment)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.
// Card BC의 card-integration-event-controller.ts(Account 이벤트 구독)와 동일한
// 위치·역할이다 — Account가 Payment를 Adapter로 조회하지 않는 것처럼, Payment도
// Account를 직접 참조하지 않는다. 자기 도메인의 유스케이스(Command)만 호출하고,
// 예외는 그대로 throw하여 OutboxConsumer가 메시지를 삭제하지 않고 재시도를 담당하게 한다.
@Injectable()
export class AccountIntegrationEventController {
  private readonly logger = new Logger(AccountIntegrationEventController.name)

  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('payment.completed.v1')
  public async onPaymentCompleted(event: { paymentId: string; accountId: string; amount: number }): Promise<void> {
    this.logger.log({ message: 'payment.completed.v1 수신', payment_id: event.paymentId, account_id: event.accountId })
    await this.commandBus.execute(new WithdrawByPaymentCommand({
      accountId: event.accountId,
      amount: event.amount,
      referenceId: event.paymentId
    }))
  }

  @HandleIntegrationEvent('payment.cancelled.v1')
  public async onPaymentCancelled(event: { paymentId: string; accountId: string; amount: number }): Promise<void> {
    this.logger.log({ message: 'payment.cancelled.v1 수신', payment_id: event.paymentId, account_id: event.accountId })
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
    this.logger.log({ message: 'refund.approved.v1 수신', refund_id: event.refundId, account_id: event.accountId })
    await this.commandBus.execute(new DepositByPaymentCommand({
      accountId: event.accountId,
      amount: event.amount,
      referenceId: event.refundId
    }))
  }
}
