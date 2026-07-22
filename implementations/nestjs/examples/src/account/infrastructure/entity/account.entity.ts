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

  // The Level 1 idempotency state for the account.apply-daily-interest Task — the single
  // source of truth for whether interest was already paid today (see applyInterest() in account/domain/account.ts).
  @Column({ type: 'timestamp', nullable: true })
  lastInterestPaidAt: Date | null
}
