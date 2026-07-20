import { Column, DeleteDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('order')
export class OrderEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  orderId: string

  @Column()
  status: string

  @DeleteDateColumn()
  deletedAt: Date | null
}
