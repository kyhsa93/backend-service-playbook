import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { RefundQuery } from '@/payment/application/query/refund-query'
import { GetRefundsResult } from '@/payment/application/query/refund-result'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

// The Refund table itself has no ownerId (Refund references the original payment only via
// paymentId) — ownership is verified by querying the Payment table first. The same pattern as
// Account's AccountQueryImpl.getTransactions confirming account ownership before querying the
// transaction history.
@Injectable()
export class RefundQueryImpl extends RefundQuery {
  constructor(
    @InjectRepository(PaymentEntity) private readonly paymentRepo: Repository<PaymentEntity>,
    @InjectRepository(RefundEntity) private readonly refundRepo: Repository<RefundEntity>
  ) {
    super()
  }

  public async getRefunds(query: {
    paymentId: string
    ownerId: string
    take: number
    page: number
  }): Promise<GetRefundsResult> {
    const payment = await this.paymentRepo.createQueryBuilder('payment')
      .where('payment.paymentId = :paymentId', { paymentId: query.paymentId })
      .andWhere('payment.ownerId = :ownerId', { ownerId: query.ownerId })
      .getOne()
    if (!payment) throw new Error(ErrorMessage['Payment not found.'])

    const qb = this.refundRepo.createQueryBuilder('refund')
      .where('refund.paymentId = :paymentId', { paymentId: query.paymentId })
      .orderBy('refund.createdAt', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    const [rows, count] = await qb.getManyAndCount()

    return {
      refunds: rows.map((row) => ({
        refundId: row.refundId,
        paymentId: row.paymentId,
        amount: row.amount,
        reason: row.reason,
        status: row.status,
        decisionNote: row.decisionNote ?? undefined,
        createdAt: row.createdAt
      })),
      count
    }
  }
}
