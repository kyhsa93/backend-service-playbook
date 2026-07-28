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

  // A correlation key filled in only by a Payment BC reaction (withdraw-by-payment/
  // deposit-by-payment) — used for idempotency checks (don't reprocess if a transaction with the same referenceId already exists).
  @Column({ type: 'varchar', nullable: true })
  referenceId: string | null

  // The payee/memo optionally attached to a withdrawal at request time — see transaction.ts.
  @Column({ type: 'varchar', nullable: true })
  merchantName: string | null

  // Filled in asynchronously by CategorizeTransactionHandler — null until that reaction runs
  // (or forever, for a transaction with no merchantName to classify).
  @Column({ type: 'varchar', nullable: true })
  category: string | null

  @CreateDateColumn()
  createdAt: Date
}
