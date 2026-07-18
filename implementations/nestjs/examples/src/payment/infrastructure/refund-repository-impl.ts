import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { Refund } from '@/payment/domain/refund'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { RefundStatus } from '@/payment/payment-enum'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'

@Injectable()
export class RefundRepositoryImpl extends RefundRepository {
  constructor(
    @InjectRepository(RefundEntity) private readonly refundRepo: Repository<RefundEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async findRefunds(query: {
    readonly take: number
    readonly page: number
    readonly refundId?: string
    readonly paymentId?: string
    readonly status?: RefundStatus[]
  }): Promise<{ refunds: Refund[]; count: number }> {
    const qb = this.refundRepo.createQueryBuilder('refund')
      .orderBy('refund.refundId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.refundId) qb.andWhere('refund.refundId = :refundId', { refundId: query.refundId })
    if (query.paymentId) qb.andWhere('refund.paymentId = :paymentId', { paymentId: query.paymentId })
    if (query.status?.length) qb.andWhere('refund.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      refunds: rows.map((row) => new Refund({
        refundId: row.refundId,
        paymentId: row.paymentId,
        amount: row.amount,
        reason: row.reason,
        status: row.status as RefundStatus,
        decisionNote: row.decisionNote ?? undefined,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async saveRefund(refund: Refund): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(RefundEntity, {
      refundId: refund.refundId,
      paymentId: refund.paymentId,
      amount: refund.amount,
      reason: refund.reason,
      status: refund.status,
      decisionNote: refund.decisionNote ?? null,
      createdAt: refund.createdAt
    })

    if (refund.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(refund.domainEvents)
      refund.clearEvents()
    }
  }
}
