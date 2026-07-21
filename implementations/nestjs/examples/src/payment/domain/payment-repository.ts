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

  // payment.send-card-statements Task가 카드별 월간 사용내역(건수 + 총액)을 집계하는
  // 전용 쿼리다. findPayments로 전체 row를 읽어와 애플리케이션에서 합산하면 카드당
  // 결제 건수가 많을 때 take 상한에 걸려 부정확해질 수 있어, DB 집계 함수로 직접
  // count/sum을 구한다(payment/application/command/send-card-statements-command-handler.ts 참고).
  abstract summarizePayments(query: {
    readonly cardId: string
    readonly status: PaymentStatus[]
    readonly createdAtFrom: Date
    readonly createdAtTo: Date
  }): Promise<{ count: number; totalAmount: number }>
}
