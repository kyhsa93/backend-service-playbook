import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { RefundReasonInsightsQuery } from '@/payment/application/query/refund-reason-insights-query'
import { RefundReasonInsightsResult } from '@/payment/application/query/refund-reason-insights-result'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'

@Injectable()
export class RefundReasonInsightsQueryImpl extends RefundReasonInsightsQuery {
  constructor(@InjectRepository(RefundEntity) private readonly refundRepo: Repository<RefundEntity>) {
    super()
  }

  public async getInsights(query: { fromDate?: string; toDate?: string }): Promise<RefundReasonInsightsResult> {
    let qb = this.refundRepo.createQueryBuilder('refund')
      .select('refund.reasonCategory', 'category')
      .addSelect('COUNT(*)', 'count')
      .where('refund.reasonCategory IS NOT NULL')

    if (query.fromDate) qb = qb.andWhere('refund.createdAt >= :fromDate', { fromDate: `${query.fromDate}T00:00:00.000Z` })
    if (query.toDate) qb = qb.andWhere('refund.createdAt <= :toDate', { toDate: `${query.toDate}T23:59:59.999Z` })

    const rows = await qb.groupBy('refund.reasonCategory').getRawMany<{ category: string; count: string }>()

    const counts = rows.map((row) => ({ category: row.category, count: Number(row.count) }))
    const totalClassified = counts.reduce((sum, row) => sum + row.count, 0)

    return { counts, totalClassified }
  }
}
