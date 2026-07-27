import { Column, Entity, Index, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('spending_analysis')
@Index('IDX_spending_analysis_accountId_analysisMonth', ['accountId', 'analysisMonth'], { unique: true })
export class SpendingAnalysisEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  analysisId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  @Column({ type: 'char', length: 7 })
  analysisMonth: string

  @Column('int')
  totalAmount: number

  @Column('int')
  transactionCount: number

  @Column('int')
  averageAmount: number

  @Column('int')
  changeFromPreviousMonth: number

  @Column()
  trend: string
}
