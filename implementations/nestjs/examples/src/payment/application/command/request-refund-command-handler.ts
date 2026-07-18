import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/payment/application/event/outbox-relay'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { Refund } from '@/payment/domain/refund'
import { RefundEligibilityService } from '@/payment/domain/refund-eligibility-service'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@CommandHandler(RequestRefundCommand)
export class RequestRefundCommandHandler implements ICommandHandler<RequestRefundCommand, Refund> {
  // RefundEligibilityService는 프레임워크 데코레이터가 없는 순수 Domain Service다.
  // NestJS DI 컨테이너에 등록하지 않고 직접 인스턴스화해 쓴다(상태 없는 순수 판단
  // 로직이라 매 요청 재사용에 문제가 없다).
  private readonly refundEligibilityService = new RefundEligibilityService()

  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly refundRepository: RefundRepository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: RequestRefundCommand): Promise<Refund> {
    const payment = await this.paymentRepository
      .findPayments({ paymentId: command.paymentId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.payments.pop())
    if (!payment) throw new Error(ErrorMessage['결제를 찾을 수 없습니다.'])

    const refund = Refund.create({ paymentId: payment.paymentId, amount: command.amount, reason: command.reason })

    // 어느 한 Aggregate만으로는 내릴 수 없는 판단(원 결제 상태 + 환불 금액 비교)을
    // Payment+Refund 두 Aggregate를 함께 로드한 이 Application 레이어가
    // RefundEligibilityService(Domain Service)에 위임해 조율한다.
    const decision = this.refundEligibilityService.evaluate(payment, refund)
    if (decision.approved) {
      refund.approve({ accountId: payment.accountId, ownerId: payment.ownerId })
    } else {
      // 환불 거부는 도메인 관점에서 유효한 상태 전이다(입력이 잘못된 것이 아니라 두
      // Aggregate를 조율해 내린 결론) — 따라서 이 메서드는 throw하지 않고 REJECTED로
      // 저장한 Refund를 그대로 반환한다. 인터페이스 레이어가 이를 에러가 아닌
      // 201 + status:REJECTED로 응답한다.
      refund.reject(decision.reason ?? '환불 요청이 거부되었습니다.')
    }

    await this.transactionManager.run(async () => {
      await this.refundRepository.saveRefund(refund)
    })
    // RefundApproved → refund.approved.v1을 Account BC가 구독해 환불 크레딧을 실행한다.
    // 거부된 경우에는 Domain Event가 없으므로 드레인할 것이 없어 no-op이다.
    await this.outboxRelay.processPending()
    return refund
  }
}
