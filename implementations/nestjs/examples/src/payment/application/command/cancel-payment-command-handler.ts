import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelPaymentCommand } from '@/payment/application/command/cancel-payment-command'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@CommandHandler(CancelPaymentCommand)
export class CancelPaymentCommandHandler implements ICommandHandler<CancelPaymentCommand, Payment> {
  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelPaymentCommand): Promise<Payment> {
    const payment = await this.paymentRepository
      .findPayments({ paymentId: command.paymentId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.payments.pop())
    if (!payment) throw new Error(ErrorMessage['Payment not found.'])

    payment.cancel(command.reason)
    await this.transactionManager.run(async () => {
      await this.paymentRepository.savePayment(payment)
    })
    // Account BC subscribes to PaymentCancelled → payment.cancelled.v1 and executes the compensating credit.
    // The actual subscription/execution is handled asynchronously by OutboxPoller/OutboxConsumer.
    return payment
  }
}
