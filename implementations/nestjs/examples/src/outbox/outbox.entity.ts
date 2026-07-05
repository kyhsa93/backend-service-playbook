import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('outbox')
export class OutboxEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  eventId: string

  @Column()
  eventType: string

  @Column('text')
  payload: string

  @Column({ default: false })
  processed: boolean

  @CreateDateColumn()
  createdAt: Date
}
