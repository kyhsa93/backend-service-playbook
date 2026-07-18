import { Column, Entity, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('refund')
export class RefundEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  refundId: string

  @Column({ type: 'char', length: 32 })
  paymentId: string

  @Column('int')
  amount: number

  @Column()
  reason: string

  @Column()
  status: string

  @Column({ type: 'varchar', nullable: true })
  decisionNote: string | null
}
