import { Payment } from '@/payment/domain/payment'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { Refund } from '@/payment/domain/refund'

export interface RefundDecision {
  readonly approved: boolean
  readonly reason?: string
}

// A Domain Service — a plain class with no framework decorators (it's not registered in the
// NestJS DI container either. The Application layer creates it directly with `new` when needed).
//
// The judgment "the original payment must be COMPLETED, and the refund amount can't exceed
// the payment amount" can't be made by Payment alone or Refund alone. Payment doesn't know
// about refund attempts against itself (a refund exists only as a Refund Aggregate), and
// Refund doesn't know the original payment's amount·status (it only references it via
// paymentId). Making this judgment requires loading both Aggregates and comparing them side
// by side, so this coordination logic can't go on either Aggregate's method (doing so would
// require receiving the entire other Aggregate as a parameter, collapsing the boundary) — it
// belongs here, in a separate Domain Service. (See the root docs/architecture/domain-service.md.)
export class RefundEligibilityService {
  public evaluate(payment: Payment, refund: Refund): RefundDecision {
    if (payment.status !== PaymentStatus.COMPLETED) {
      return { approved: false, reason: PaymentErrorMessage['A refund can only be requested for a completed payment.'] }
    }
    if (refund.amount > payment.amount) {
      return { approved: false, reason: PaymentErrorMessage['The refund amount cannot exceed the payment amount.'] }
    }
    return { approved: true }
  }
}
