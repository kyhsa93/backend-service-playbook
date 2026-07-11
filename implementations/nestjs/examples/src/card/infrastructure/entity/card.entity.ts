import {
  Column, CreateDateColumn, DeleteDateColumn, Entity,
  PrimaryColumn, UpdateDateColumn
} from 'typeorm'

@Entity('card')
export class CardEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  cardId: string

  @Column({ type: 'char', length: 32 })
  accountId: string

  @Column()
  ownerId: string

  @Column()
  brand: string

  @Column()
  status: string

  @CreateDateColumn()
  createdAt: Date

  @UpdateDateColumn()
  updatedAt: Date

  @DeleteDateColumn()
  deletedAt: Date | null
}
