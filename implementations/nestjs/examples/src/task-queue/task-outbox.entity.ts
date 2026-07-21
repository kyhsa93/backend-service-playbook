import { Column, CreateDateColumn, Entity, Index, PrimaryColumn } from 'typeorm'

// outbox/outbox.entity.ts(Domain Event용)와 같은 모양의 별도 테이블이다. Domain
// Event("X가 일어났다")와 Task("X를 수행하라")는 의미가 다른 별개 개념이므로
// (docs/architecture/scheduling.md#task-vs-domain-event) 같은 테이블을 공유하지 않고
// 각자의 outbox를 둔다.
@Entity('task_outbox')
@Index(['processed', 'createdAt'])
export class TaskOutboxEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  taskId: string

  @Column()
  taskType: string

  @Column('text')
  payload: string

  @Column()
  groupId: string

  @Column()
  deduplicationId: string

  @Column('int', { nullable: true })
  delaySeconds: number | null

  @Column({ default: false })
  processed: boolean

  @CreateDateColumn()
  createdAt: Date
}
