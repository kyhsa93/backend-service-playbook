import { Payment } from '@/payment/domain/payment'

export class Refund {
  public readonly refundId: string
  public readonly payment: Payment
  public readonly amount: number

  constructor(params: { refundId: string; payment: Payment; amount: number }) {
    this.refundId = params.refundId
    this.payment = params.payment
    this.amount = params.amount
  }
}
