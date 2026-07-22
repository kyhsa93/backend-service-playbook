import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@CommandHandler(RequestRefundCommand)
export class RequestRefundCommandHandler implements ICommandHandler<RequestRefundCommand, Refund> {
  // RefundEligibilityService is a plain Domain Service with no framework decorators.
  // It's instantiated directly rather than registered in the NestJS DI container (since it's
  // stateless, pure judgment logic, reusing it per request is fine).
  private readonly refundEligibilityService = new RefundEligibilityService()

  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly refundRepository: RefundRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: RequestRefundCommand): Promise<Refund> {
    const payment = await this.paymentRepository
      .findPayments({ paymentId: command.paymentId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.payments.pop())
    if (!payment) throw new Error(ErrorMessage['결제를 찾을 수 없습니다.'])

    const refund = Refund.create({ paymentId: payment.paymentId, amount: command.amount, reason: command.reason })

    // This Application layer, having loaded both the Payment and Refund Aggregates together,
    // delegates the judgment (comparing the original payment's status + the refund amount)
    // that neither Aggregate alone could make to RefundEligibilityService (a Domain Service) to coordinate.
    const decision = this.refundEligibilityService.evaluate(payment, refund)
    if (decision.approved) {
      refund.approve({ accountId: payment.accountId, ownerId: payment.ownerId })
    } else {
      // A refund rejection is a valid state transition from the domain's point of view (not
      // invalid input, but a conclusion reached by coordinating both Aggregates) — so this
      // method doesn't throw, and instead returns the Refund saved as REJECTED as-is. The
      // interface layer responds with this as 201 + status:REJECTED, not an error.
      refund.reject(decision.reason ?? '환불 요청이 거부되었습니다.')
    }

    await this.transactionManager.run(async () => {
      await this.refundRepository.saveRefund(refund)
    })
    // Account BC subscribes to RefundApproved → refund.approved.v1 and executes the refund credit.
    // When rejected, there's no Domain Event, so nothing was written to the outbox. The actual
    // subscription/execution is handled asynchronously by OutboxPoller/OutboxConsumer.
    return refund
  }
}
