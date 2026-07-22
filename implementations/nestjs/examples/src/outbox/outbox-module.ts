import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxConsumer } from '@/outbox/outbox-consumer'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxPoller } from '@/outbox/outbox-poller'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { SQS_CLIENT, SqsClientProvider } from '@/outbox/sqs-client-provider'

// This removes the structure where Account/Payment each had their own separate OutboxRelay,
// and this single global module has one outbox table + one Poller + one Consumer + one
// registry (the same "single shared outbox" convention as the Go/Java/Kotlin/FastAPI
// implementations). OutboxPoller/OutboxConsumer are registered as providers only here and
// never exported to another module — no code, including a Command Handler, may ever inject
// and call them directly (see docs/architecture/domain-events.md — synchronous draining is prohibited).
//
// SQS_CLIENT is included in exports — the task-queue/ module reuses the same SDK client when
// publishing/receiving the Task Queue (a separate SQS queue from the Domain Event one)
// (see docs/architecture/scheduling.md — "reuses the same SDK/infrastructure as the existing Outbox → SQS structure").
@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter, EventHandlerRegistry, SqsClientProvider, OutboxPoller, OutboxConsumer],
  exports: [TransactionManager, OutboxWriter, EventHandlerRegistry, SQS_CLIENT]
})
export class OutboxModule {}
