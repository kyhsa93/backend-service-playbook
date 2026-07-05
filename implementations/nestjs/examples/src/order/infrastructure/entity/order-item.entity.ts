import {
  Column, DeleteDateColumn, Entity, ManyToOne, PrimaryGeneratedColumn
} from 'typeorm'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'

@Entity('order_item')
export class OrderItemEntity {
  @PrimaryGeneratedColumn()
  id: number

  @Column()
  orderId: string

  @ManyToOne(() => OrderEntity, (order) => order.items)
  order: OrderEntity

  @Column()
  itemId: number

  @Column()
  name: string

  @Column('int')
  price: number

  @Column('int')
  quantity: number

  @DeleteDateColumn()
  deletedAt: Date | null
}
