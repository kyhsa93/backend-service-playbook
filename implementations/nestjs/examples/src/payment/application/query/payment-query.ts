import { GetPaymentResult, GetPaymentsResult } from '@/payment/application/query/payment-result'
import { PaymentStatus } from '@/payment/payment-enum'

export abstract class PaymentQuery {
  abstract getPayment(param: { paymentId: string; ownerId: string }): Promise<GetPaymentResult>

  abstract getPayments(query: {
    ownerId: string
    take: number
    page: number
    status?: PaymentStatus[]
  }): Promise<GetPaymentsResult>
}
