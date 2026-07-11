import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('sent_email')
export class SentEmailEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  sentEmailId: string

  @Column()
  accountId: string

  @Column()
  eventType: string

  @Column()
  recipient: string

  @Column()
  subject: string

  @Column()
  sesMessageId: string

  @CreateDateColumn()
  sentAt: Date
}
