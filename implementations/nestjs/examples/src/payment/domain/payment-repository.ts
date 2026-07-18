import { Payment } from '@/payment/domain/payment'
import { PaymentStatus } from '@/payment/payment-enum'

export abstract class PaymentRepository {
  abstract findPayments(query: {
    readonly take: number
    readonly page: number
    readonly paymentId?: string
    readonly ownerId?: string
    readonly cardId?: string
    readonly accountId?: string
    readonly status?: PaymentStatus[]
  }): Promise<{ payments: Payment[]; count: number }>

  abstract savePayment(payment: Payment): Promise<void>
}
