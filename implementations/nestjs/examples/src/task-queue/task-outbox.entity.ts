import { Column, CreateDateColumn, Entity, Index, PrimaryColumn } from 'typeorm'

// A separate table shaped like outbox/outbox.entity.ts (for Domain Events). Since a Domain
// Event ("X happened") and a Task ("perform X") are semantically different, separate concepts
// (see docs/architecture/scheduling.md, the Task vs Domain Event section), they don't share a
// table — each has its own outbox.
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
