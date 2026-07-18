import { Column, Entity, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('payment')
export class PaymentEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  paymentId: string

  @Column({ type: 'char', length: 32 })
  cardId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  @Column()
  ownerId: string

  @Column('int')
  amount: number

  @Column()
  status: string
}
