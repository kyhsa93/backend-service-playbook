import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { PaymentQuery } from '@/payment/application/query/payment-query'
import { GetPaymentResult, GetPaymentsResult } from '@/payment/application/query/payment-result'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@Injectable()
export class PaymentQueryImpl extends PaymentQuery {
  constructor(
    @InjectRepository(PaymentEntity) private readonly paymentRepo: Repository<PaymentEntity>
  ) {
    super()
  }

  public async getPayment(param: { paymentId: string; ownerId: string }): Promise<GetPaymentResult> {
    const row = await this.paymentRepo.createQueryBuilder('payment')
      .where('payment.paymentId = :paymentId', { paymentId: param.paymentId })
      .andWhere('payment.ownerId = :ownerId', { ownerId: param.ownerId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['결제를 찾을 수 없습니다.'])

    return {
      paymentId: row.paymentId,
      cardId: row.cardId,
      accountId: row.accountId,
      ownerId: row.ownerId,
      amount: row.amount,
      status: row.status,
      createdAt: row.createdAt
    }
  }

  public async getPayments(query: {
    ownerId: string
    take: number
    page: number
    status?: PaymentStatus[]
  }): Promise<GetPaymentsResult> {
    const qb = this.paymentRepo.createQueryBuilder('payment')
      .where('payment.ownerId = :ownerId', { ownerId: query.ownerId })
      .orderBy('payment.createdAt', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.status?.length) qb.andWhere('payment.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      payments: rows.map((row) => ({
        paymentId: row.paymentId,
        cardId: row.cardId,
        accountId: row.accountId,
        ownerId: row.ownerId,
        amount: row.amount,
        status: row.status,
        createdAt: row.createdAt
      })),
      count
    }
  }
}
