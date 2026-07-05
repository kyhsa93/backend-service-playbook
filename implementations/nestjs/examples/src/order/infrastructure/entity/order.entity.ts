import {
  Column, CreateDateColumn, DeleteDateColumn, Entity,
  OneToMany, PrimaryColumn, UpdateDateColumn
} from 'typeorm'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'

@Entity('order')
export class OrderEntity {
  @PrimaryColumn()
  orderId: string

  @Column()
  userId: string

  @Column()
  status: string

  @OneToMany(() => OrderItemEntity, (item) => item.order, { cascade: true, eager: false })
  items: OrderItemEntity[]

  @CreateDateColumn()
  createdAt: Date

  @UpdateDateColumn()
  updatedAt: Date

  @DeleteDateColumn()
  deletedAt: Date | null
}
