import { Column, CreateDateColumn, Entity, Index, PrimaryColumn } from 'typeorm'

// account/infrastructure/notification/sent-email.entity.ts와 같은 모양의 발송 기록
// 테이블이다. (cardId, statementMonth) 유니크 제약이 이 Task의 실제 멱등성 방어선이다 —
// 같은 카드·같은 달 조합으로 두 번 insert되면 DB가 막는다(애플리케이션의
// hasSentStatement() 사전 체크는 최적화일 뿐, 최종 방어는 이 제약).
@Entity('sent_card_statement')
@Index(['cardId', 'statementMonth'], { unique: true })
export class SentCardStatementEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  sentCardStatementId: string

  @Column({ type: 'char', length: 32 })
  cardId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  // 'YYYY-MM' 형식 — payment/infrastructure/previous-statement-month.ts가 계산한다.
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
