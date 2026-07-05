import {
  Column, CreateDateColumn, DeleteDateColumn, Entity,
  PrimaryColumn, UpdateDateColumn
} from 'typeorm'

@Entity('account')
export class AccountEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  accountId: string

  @Column()
  ownerId: string

  @Column()
  email: string

  @Column('int')
  amount: number

  @Column({ type: 'char', length: 3 })
  currency: string

  @Column()
  status: string

  @CreateDateColumn()
  createdAt: Date

  @UpdateDateColumn()
  updatedAt: Date

  @DeleteDateColumn()
  deletedAt: Date | null
}
