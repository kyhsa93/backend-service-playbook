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

  // account.apply-daily-interest Task의 Level 1 멱등성 상태 — 오늘 이미 이자를
  // 지급했는지 판단하는 유일한 소스(account/domain/account.ts의 applyInterest() 참고).
  @Column({ type: 'timestamp', nullable: true })
  lastInterestPaidAt: Date | null
}
