import { Refund } from '@/payment/domain/refund'
import { RefundStatus } from '@/payment/payment-enum'

export abstract class RefundRepository {
  abstract findRefunds(query: {
    readonly take: number
    readonly page: number
    readonly refundId?: string
    readonly paymentId?: string
    readonly status?: RefundStatus[]
  }): Promise<{ refunds: Refund[]; count: number }>

  abstract saveRefund(refund: Refund): Promise<void>

  // A dedicated aggregate query for RefundFraudRiskScorer's feature assembly (see
  // request-refund-command-handler.ts), the same reason PaymentRepository.summarizePayments
  // exists — counting an owner's refund history via findRefunds and counting matches in the
  // application wouldn't scale once history grows past a single page. Refund itself carries no
  // ownerId (only paymentId), so implementations must join against Payment to filter by owner.
  abstract summarizeRefundsByOwner(query: {
    readonly ownerId: string
    readonly createdAtFrom: Date
    readonly status?: RefundStatus[]
  }): Promise<{ count: number }>
}
