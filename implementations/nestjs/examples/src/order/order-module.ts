import { Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { OrderCommandService } from '@/order/application/command/order-command-service'
import { OrderQuery } from '@/order/application/query/order-query'
import { OrderQueryService } from '@/order/application/query/order-query-service'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'
import { OrderQueryImpl } from '@/order/infrastructure/order-query-impl'
import { OrderRepositoryImpl } from '@/order/infrastructure/order-repository-impl'
import { OrderController } from '@/order/interface/order-controller'

@Module({
  imports: [TypeOrmModule.forFeature([OrderEntity, OrderItemEntity])],
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    { provide: OrderQuery, useClass: OrderQueryImpl },
    { provide: OrderRepository, useClass: OrderRepositoryImpl }
  ]
})
export class OrderModule {}
