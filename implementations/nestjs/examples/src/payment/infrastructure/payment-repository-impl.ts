import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'

@Injectable()
export class PaymentRepositoryImpl extends PaymentRepository {
  constructor(
    @InjectRepository(PaymentEntity) private readonly paymentRepo: Repository<PaymentEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async findPayments(query: {
    readonly take: number
    readonly page: number
    readonly paymentId?: string
    readonly ownerId?: string
    readonly cardId?: string
    readonly accountId?: string
    readonly status?: PaymentStatus[]
  }): Promise<{ payments: Payment[]; count: number }> {
    const qb = this.paymentRepo.createQueryBuilder('payment')
      .orderBy('payment.paymentId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.paymentId) qb.andWhere('payment.paymentId = :paymentId', { paymentId: query.paymentId })
    if (query.ownerId) qb.andWhere('payment.ownerId = :ownerId', { ownerId: query.ownerId })
    if (query.cardId) qb.andWhere('payment.cardId = :cardId', { cardId: query.cardId })
    if (query.accountId) qb.andWhere('payment.accountId = :accountId', { accountId: query.accountId })
    if (query.status?.length) qb.andWhere('payment.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      payments: rows.map((row) => new Payment({
        paymentId: row.paymentId,
        cardId: row.cardId,
        accountId: row.accountId,
        ownerId: row.ownerId,
        amount: row.amount,
        status: row.status as PaymentStatus,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async savePayment(payment: Payment): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(PaymentEntity, {
      paymentId: payment.paymentId,
      cardId: payment.cardId,
      accountId: payment.accountId,
      ownerId: payment.ownerId,
      amount: payment.amount,
      status: payment.status,
      createdAt: payment.createdAt
    })

    if (payment.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(payment.domainEvents)
      payment.clearEvents()
    }
  }

  public async summarizePayments(query: {
    readonly cardId: string
    readonly status: PaymentStatus[]
    readonly createdAtFrom: Date
    readonly createdAtTo: Date
  }): Promise<{ count: number; totalAmount: number }> {
    const row = await this.paymentRepo.createQueryBuilder('payment')
      .select('COUNT(*)', 'count')
      .addSelect('COALESCE(SUM(payment.amount), 0)', 'totalAmount')
      .where('payment.cardId = :cardId', { cardId: query.cardId })
      .andWhere('payment.status IN (:...status)', { status: query.status })
      .andWhere('payment.createdAt >= :createdAtFrom', { createdAtFrom: query.createdAtFrom })
      .andWhere('payment.createdAt < :createdAtTo', { createdAtTo: query.createdAtTo })
      .getRawOne<{ count: string; totalAmount: string }>()

    return { count: Number(row?.count ?? 0), totalAmount: Number(row?.totalAmount ?? 0) }
  }
}
