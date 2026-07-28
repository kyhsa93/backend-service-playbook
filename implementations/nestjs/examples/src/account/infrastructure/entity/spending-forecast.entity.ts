import { Column, Entity, Index, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('spending_forecast')
@Index('IDX_spending_forecast_accountId_forecastMonth', ['accountId', 'forecastMonth'], { unique: true })
export class SpendingForecastEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  forecastId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  @Column({ type: 'char', length: 7 })
  forecastMonth: string

  @Column('int')
  predictedAmount: number

  @Column()
  confidence: string

  @Column('int')
  historyMonthsUsed: number
}
