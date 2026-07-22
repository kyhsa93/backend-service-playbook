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

  // A dedicated query for the payment.send-card-statements Task to aggregate each card's
  // monthly usage (count + total amount). Reading every row via findPayments and summing them
  // in the application could become inaccurate once a card has enough payments to hit the take
  // cap, so count/sum is computed directly with a DB aggregate function
  // (see payment/application/command/send-card-statements-command-handler.ts).
  abstract summarizePayments(query: {
    readonly cardId: string
    readonly status: PaymentStatus[]
    readonly createdAtFrom: Date
    readonly createdAtTo: Date
  }): Promise<{ count: number; totalAmount: number }>
}
