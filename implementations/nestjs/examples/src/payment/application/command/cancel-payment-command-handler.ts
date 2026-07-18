import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelPaymentCommand } from '@/payment/application/command/cancel-payment-command'
import { OutboxRelay } from '@/payment/application/event/outbox-relay'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@CommandHandler(CancelPaymentCommand)
export class CancelPaymentCommandHandler implements ICommandHandler<CancelPaymentCommand, Payment> {
  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: CancelPaymentCommand): Promise<Payment> {
    const payment = await this.paymentRepository
      .findPayments({ paymentId: command.paymentId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.payments.pop())
    if (!payment) throw new Error(ErrorMessage['결제를 찾을 수 없습니다.'])

    payment.cancel(command.reason)
    await this.transactionManager.run(async () => {
      await this.paymentRepository.savePayment(payment)
    })
    // PaymentCancelled → payment.cancelled.v1을 Account BC가 구독해 보상 크레딧을 실행한다.
    await this.outboxRelay.processPending()
    return payment
  }
}
