import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxWriter } from '@/outbox/outbox-writer'

@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter, EventHandlerRegistry],
  exports: [TransactionManager, OutboxWriter, EventHandlerRegistry]
})
export class OutboxModule {}
