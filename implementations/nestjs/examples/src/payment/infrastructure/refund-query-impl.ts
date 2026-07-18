import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { RefundQuery } from '@/payment/application/query/refund-query'
import { GetRefundsResult } from '@/payment/application/query/refund-result'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

// Refund 테이블 자체는 ownerId를 갖지 않는다(Refund는 paymentId로만 원 결제를
// 참조한다) — 소유권 검증은 Payment 테이블을 먼저 조회해 확인한다. Account의
// AccountQueryImpl.getTransactions가 계좌 소유권을 먼저 확인한 뒤 거래 내역을
// 조회하는 것과 동일한 패턴이다.
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
    if (!payment) throw new Error(ErrorMessage['결제를 찾을 수 없습니다.'])

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
