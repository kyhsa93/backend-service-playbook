import { Column, Entity, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('card')
export class CardEntity extends BaseEntity {
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
}
