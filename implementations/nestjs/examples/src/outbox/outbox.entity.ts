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

  // The W3C traceparent header captured at write time (trace-context.ts), forwarded by
  // OutboxPoller as an SQS message attribute and re-hydrated by OutboxConsumer, so an HTTP
  // request and its async event processing land in one trace (observability.md). NULL when
  // there was no active span to capture (e.g. a Task Queue batch job).
  @Column({ type: 'varchar', nullable: true })
  traceParent: string | null

  @CreateDateColumn()
  createdAt: Date
}
