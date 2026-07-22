import { Column, CreateDateColumn, Entity, Index, PrimaryColumn } from 'typeorm'

// A sent-record table shaped like account/infrastructure/notification/sent-email.entity.ts.
// The (cardId, statementMonth) unique constraint is this Task's actual idempotency defense
// line — the DB blocks a second insert with the same card·month combination (the
// application's hasSentStatement() precheck is just an optimization; this constraint is the final defense).
@Entity('sent_card_statement')
@Index(['cardId', 'statementMonth'], { unique: true })
export class SentCardStatementEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  sentCardStatementId: string

  @Column({ type: 'char', length: 32 })
  cardId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  // 'YYYY-MM' format — computed by payment/infrastructure/previous-statement-month.ts.
  @Column({ type: 'char', length: 7 })
  statementMonth: string

  @Column('int')
  paymentCount: number

  @Column('int')
  totalAmount: number

  @Column({ type: 'char', length: 3 })
  currency: string

  @Column()
  recipient: string

  @Column()
  sesMessageId: string

  @CreateDateColumn()
  sentAt: Date
}
