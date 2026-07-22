import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { OutboxModule } from '@/outbox/outbox-module'

import { TaskConsumerRegistry } from './task-consumer-registry'
import { TaskOutboxEntity } from './task-outbox.entity'
import { TaskOutboxRelay } from './task-outbox-relay'
import { TaskQueue } from './task-queue'
import { TaskQueueConsumer } from './task-queue-consumer'
import { TaskQueueOutbox } from './task-queue-outbox'

// The shared Task Queue infrastructure module (shaped like outbox/outbox-module.ts) — it has
// one task_outbox table + one Relay + one Consumer + one registry. It's @Global, but it still
// needs to be included once in AppModule's imports to activate it
// (see docs/architecture/scheduling.md, the AppModule Configuration section).
//
// The reason it imports OutboxModule is to reuse SQS_CLIENT (the same SDK connection) —
// OutboxModule is already @Global so injection would work even without an explicit import, but
// it's stated explicitly to make it visible in the code that this module actually depends on SQS_CLIENT.
@Global()
@Module({
  imports: [TypeOrmModule.forFeature([TaskOutboxEntity]), OutboxModule],
  providers: [
    TaskConsumerRegistry,
    TaskQueueConsumer,
    TaskOutboxRelay,
    { provide: TaskQueue, useClass: TaskQueueOutbox }
  ],
  exports: [TaskQueue]
})
export class TaskQueueModule {}
