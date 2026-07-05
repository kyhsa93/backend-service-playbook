import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxWriter } from '@/outbox/outbox-writer'

@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter],
  exports: [TransactionManager, OutboxWriter]
})
export class OutboxModule {}
