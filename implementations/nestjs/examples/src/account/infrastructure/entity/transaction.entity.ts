import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('transaction')
export class TransactionEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  transactionId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  @Column()
  type: string

  @Column('int')
  amount: number

  @Column({ type: 'char', length: 3 })
  currency: string

  // Payment BC 반응(withdraw-by-payment/deposit-by-payment)에서만 채워지는 상관관계
  // 키 — 멱등성 판단(같은 referenceId의 거래가 이미 있으면 재처리하지 않음)에 쓰인다.
  @Column({ type: 'varchar', nullable: true })
  referenceId: string | null

  @CreateDateColumn()
  createdAt: Date
}
