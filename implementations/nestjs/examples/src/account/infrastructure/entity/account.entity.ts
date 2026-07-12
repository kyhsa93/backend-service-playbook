import { Column, Entity, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('account')
export class AccountEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  accountId: string

  @Column()
  ownerId: string

  @Column()
  email: string

  @Column('int')
  amount: number

  @Column({ type: 'char', length: 3 })
  currency: string

  @Column()
  status: string
}
