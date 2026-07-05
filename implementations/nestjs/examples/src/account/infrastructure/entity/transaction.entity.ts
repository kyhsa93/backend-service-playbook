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

  @CreateDateColumn()
  createdAt: Date
}
