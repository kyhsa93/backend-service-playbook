# Scheduling / Batch Jobs

> **What kind of document this is**: The full `@TaskConsumer` + `TaskQueue` + `TaskOutbox` structure described here is **one implementation example**. It is not the only approach this guide mandates — depending on your team's situation, you may start with something much simpler. A single plain `@Cron`, one simple SQS consumer, or an external queue service (BullMQ, Sidekiq, etc.) are all valid choices. Read this document as a **reference design for cases where scale, multiple instances, and transactional consistency matter**.
>
> The **minimum requirements** are just these three:
> - Put the Scheduler in the **Infrastructure layer** (where the `@Cron` decorator lives).
> - Make the Task handler (whatever form it takes) **idempotent**.
> - If you use SQS, default to **FIFO + DLQ**.
>
> Treat the detailed structure below as a **complete implementation example** built on top of these minimum requirements.

Periodic jobs and batch processing are implemented using an **AWS SQS-based Task Queue**. The Scheduler produces Task messages onto SQS on a Cron cycle, and **a Task Controller method subscribes via the `@TaskConsumer` decorator**, so the method runs when that Task arrives. The Task Controller injects the Command Service and executes the Command corresponding to the Task.

```
[Scheduler/CommandService] --(DB insert)--> [task_outbox table]
                                                  ↓ (Cron polling)
                                          [TaskOutboxRelay] --(SendMessage)--> [SQS]
                                                                                  ↓
                                                                        [TaskQueueConsumer]
                                                                                  ↓ taskType routing
                                                          [TaskController.method @TaskConsumer('...')]
                                                                                  ↓
                                                                    [CommandService.xxxCommand(...)]
```

Enqueuing is always defined as **writing a row to the `task_outbox` table**, and `TaskOutboxRelay` polls it on a short cycle and publishes to SQS. This pattern binds the Command transaction and the Task enqueue into the same transaction, eliminating the dual-write problem (the same reasoning as the Domain Event's Outbox pattern).

The reason `@nestjs/schedule`'s `@Cron` is not attached directly to the Application Service is as follows.

- **Safety with multiple instances**: with SQS FIFO queue's `MessageDeduplicationId`, even if several instances enqueue at the same moment, only one message actually enters the queue within the 5-minute deduplication window.
- **Built-in retry**: if the Consumer doesn't delete the message due to an exception, it's automatically re-received after the `VisibilityTimeout` elapses. Once `maxReceiveCount` is exceeded, it moves to the DLQ.
- **Observability**: track status via CloudWatch metrics (`ApproximateNumberOfMessages`, `ApproximateAgeOfOldestMessage`) and the DLQ.
- **Backpressure**: even if workload spikes, messages queue up and get consumed at the Consumer's processing rate.

It reuses the same SDK/infrastructure as the existing Outbox → SQS structure. It's the same kind of structure as the `EventConsumer`/`EventHandlerRegistry` pattern in [domain-events.md](./domain-events.md).

## Table of Contents

- [Task vs Domain Event](#task-vs-domain-event) — when to use a Task, when to use an Event
- [Installation](#installation) · [AppModule Configuration](#appmodule-configuration) · [Queue Configuration](#queue-configuration) · [Layer Placement](#layer-placement)
- Components (in implementation order):
  - [`@TaskConsumer` Decorator](#taskconsumer-decorator)
  - [`TaskConsumerRegistry` — Routing](#taskconsumerregistry--routing)
  - [`TaskQueueConsumer` — SQS Polling](#taskqueueconsumer--sqs-polling)
  - [`TaskController` — Executing Commands (Interface Layer)](#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer)
  - [`TaskQueue` — Outbox-based Implementation](#taskqueue--outbox-based-implementation)
  - [Scheduler — Cron → TaskQueue](#scheduler--cron--taskqueue)
- [Module Registration](#module-registration) · [Ad-hoc Task Enqueuing](#ad-hoc-task-enqueuing-inside-a-transaction)
- Operations / policy: [MessageGroupId Strategy](#messagegroupid-strategy) · [Idempotency](#idempotency) · [Payload Validation](#payload-validation) · [Long-running Tasks and VisibilityTimeout Heartbeats](#long-running-tasks-and-visibilitytimeout-heartbeats)
- [Graceful Shutdown](#graceful-shutdown) · [DLQ Monitoring](#dlq-monitoring) · [Testing](#testing) · [Interval / Timeout](#interval--timeout) · [Principles](#principles)

## Task vs Domain Event

Both pass through SQS, but **their purpose and consumption model differ**. To avoid confusion, choose based on the criteria below.

| | **Task Queue** | **Domain Event** |
|---|---|---|
| Purpose | An asynchronous job implemented and used as needed (batch, Cron, decoupled follow-up processing) | Receives and processes the **result (fact)** of executing a Command |
| Semantic unit | Imperative: "perform X" | Declarative past tense: "X happened" |
| Number of handlers | **1:1** — exactly one Task Controller method per `taskType` | **1:N** — one event is subscribed to by multiple EventHandlers fanning out |
| Producer | Scheduler (Cron) / Application Service calls `TaskQueue.enqueue` → saved to `task_outbox` → `TaskOutboxRelay` publishes to SQS | The Aggregate pushes to `domainEvents` → the Repository saves it to the `outbox` table → `OutboxPoller` publishes to SQS |
| Example | "Run the expired-order cleanup batch", "resend a notification", "generate a report" | For an `OrderCancelled` event, drive each handler for refund, restocking, and notification |
| Failure handling | visibility timeout retry → DLQ | Same (per handler) |
| Guide | This document | [domain-events.md](./domain-events.md) |

Core deciding question: **"Is this observing the result of a Command?"** If so, it's a Domain Event. If instead it's "I want to run this job asynchronously," it's a Task.

## Installation

```bash
npm install @aws-sdk/client-sqs @nestjs/schedule
```

Locally, SQS is provided via LocalStack. See [local-dev.md](./local-dev.md) for how to create queues.

## AppModule Configuration

For the `@Cron` decorator to work, `ScheduleModule.forRoot()` must be registered. `TaskQueueModule` is `@Global()`, but it must still be included once in AppModule's `imports` to activate it.

```typescript
// src/app-module.ts
import { Module } from '@nestjs/common'
import { ScheduleModule } from '@nestjs/schedule'

import { OrderModule } from '@/order/order-module'
import { TaskQueueModule } from '@/task-queue/task-queue-module'

@Module({
  imports: [
    ScheduleModule.forRoot(),   // enables @Cron — without it, the Scheduler/Relay silently doesn't run
    TaskQueueModule,            // @Global, but importing it once is still required
    OrderModule
  ]
})
export class AppModule {}
```

## Queue Configuration

### Single Task queue policy

**Tasks from all domains share a single SQS FIFO queue.** Queues are not split per domain.

- **Simplified operations**: a single set of queue/DLQ/alarms keeps infrastructure, IaC, and monitoring simple.
- **Single-DLQ visibility**: failures gather in one place, making tracking and redrive easy.
- **Minimal isolation benefit at the current scale**: until throughput exceeds thousands of msg/s, or there's a clear case where one domain's failures block another domain's processing, the complexity cost of splitting outweighs the isolation benefit.

Routing is performed not by queue but by the **`taskType` string**. When each domain's Task Controller declares its taskType with the `@TaskConsumer('<domain>.<action>')` naming convention, branching happens naturally within a single queue.

### Queue types

| Queue type | Selection criteria | Key properties |
|---------|----------|-----------|
| **FIFO (`*.fifo`)** — **default** | Need to prevent duplicate enqueuing and guarantee per-group ordering (most cases) | `MessageGroupId`, `MessageDeduplicationId` |
| **Standard** | Need extremely high throughput (thousands of msg/s) and handle ordering/dedup at the app level | at-least-once, order not guaranteed |

**A DLQ is mandatory.** Messages exceeding `RedrivePolicy`'s `maxReceiveCount` move to the DLQ, preventing infinite retries of poison messages.

```
SQS_TASK_QUEUE_URL=https://.../app-task.fifo
SQS_TASK_DLQ_URL=https://.../app-task-dlq.fifo
```

## Layer Placement

**Place the Task Controller in the Interface layer.** Just like the HTTP Controller, it's an **input adapter** that receives external input (an SQS message) and delegates to the Application Service. For the same reason the REST entry point lives in `interface/`, the message entry point also lives in `interface/`.

The shared Task Queue infrastructure (SQS polling/enqueuing, decorator, registry) doesn't belong to any domain, so it lives in a top-level shared module.

```
src/
  common/
    is-unique-violation.ts                   # helper to detect a Postgres unique violation
  task-queue/                                # shared Task Queue infrastructure
    task-queue-module.ts                     # @Global module
    task-queue.ts                            # TaskQueue interface (abstract class)
    task-queue-outbox.ts                     # Outbox-based TaskQueue implementation
    task-outbox.entity.ts                    # task_outbox table Entity
    task-outbox-relay.ts                     # publishes task_outbox → SQS (Cron polling)
    task-execution-log.ts                    # TaskExecutionLog interface (abstract)
    task-execution-log-db.ts                 # DB-based implementation
    task-execution-log.entity.ts             # task_execution_log table Entity
    task-execution-log-cleaner.ts            # ledger cleanup (Cron)
    task-consumer.decorator.ts               # @TaskConsumer decorator
    task-consumer-registry.ts                # taskType → handler routing
    task-queue-consumer.ts                   # SQS polling → registry.dispatch
  order/
    interface/
      order-controller.ts                    # HTTP input adapter
      order-task-controller.ts               # Task input adapter — @TaskConsumer methods
    infrastructure/
      order-cleanup-scheduler.ts             # @Cron → TaskQueue.enqueue
    application/
      command/
        order-command-service.ts             # business logic — injects only the TaskQueue interface
```

## `@TaskConsumer` Decorator

Binds a Task type to a method. Handlers are registered in a global Map, and at runtime `TaskConsumerRegistry` looks them up by `taskType`.

```typescript
// src/task-queue/task-consumer.decorator.ts
export type HeartbeatConfig = {
  intervalMs: number      // how often ChangeMessageVisibility is called
  extendSeconds: number   // how long to extend on each call
}

type HandlerEntry = {
  handlerClass: new (...args: unknown[]) => unknown
  method: string
  heartbeat?: HeartbeatConfig
  idempotencyKey?: (payload: any) => string
}

const TASK_HANDLER_MAP = new Map<string, HandlerEntry>()

export type TaskConsumerOptions = {
  heartbeat?: HeartbeatConfig
  idempotencyKey?: (payload: any) => string
}

export function TaskConsumer(taskType: string, options?: TaskConsumerOptions): MethodDecorator {
  return (target, propertyKey) => {
    if (TASK_HANDLER_MAP.has(taskType)) {
      throw new Error(`Duplicate @TaskConsumer for taskType: ${taskType}`)
    }
    TASK_HANDLER_MAP.set(taskType, {
      handlerClass: target.constructor as new (...args: unknown[]) => unknown,
      method: propertyKey as string,
      heartbeat: options?.heartbeat,
      idempotencyKey: options?.idempotencyKey
    })
  }
}

export function getTaskHandler(taskType: string): HandlerEntry | undefined {
  return TASK_HANDLER_MAP.get(taskType)
}
```

- **`heartbeat` option (optional)**: for long-running Tasks only, specifying `{ intervalMs, extendSeconds }` makes `TaskQueueConsumer` periodically call `ChangeMessageVisibility` while processing. See [Long-running Tasks and VisibilityTimeout Heartbeats](#long-running-tasks-and-visibilitytimeout-heartbeats) below for details.
- **`idempotencyKey` option (optional)**: specifying a function that extracts a unique key from the payload makes `TaskConsumerRegistry` **block duplicate execution at the framework level using `TaskExecutionLog`** before dispatch. The Task Controller doesn't need to write any ledger code. See [Idempotency](#idempotency) for details.
- **`taskType` is globally unique**: exactly one handler must run per Task. Duplicate registration fails immediately at bootstrap.
- **Registration happens at class evaluation time (import time)**: the decorator adds an entry to `TASK_HANDLER_MAP` when the file is imported and the class body is evaluated. So the **Task Controller must be registered in some module's `providers`** so that the file gets imported during module loading and the decorator fires. If it's not in providers, the class file itself never loads, and `TaskQueueConsumer` won't be able to find that `taskType`.

## `TaskConsumerRegistry` — Routing

Finds and calls the Task Controller method mapped to a `taskType`.

```typescript
// src/task-queue/task-consumer-registry.ts
import { Injectable, Logger } from '@nestjs/common'
import { ModuleRef } from '@nestjs/core'

import { HeartbeatConfig, getTaskHandler } from './task-consumer.decorator'
import { TaskExecutionLog } from './task-execution-log'

@Injectable()
export class TaskConsumerRegistry {
  private readonly logger = new Logger(TaskConsumerRegistry.name)

  constructor(
    private readonly moduleRef: ModuleRef,
    private readonly executionLog: TaskExecutionLog
  ) {}

  public getHeartbeat(taskType: string): HeartbeatConfig | undefined {
    return getTaskHandler(taskType)?.heartbeat
  }

  public async dispatch(taskType: string, payload: object): Promise<void> {
    const entry = getTaskHandler(taskType)
    if (!entry) {
      throw new Error(`No @TaskConsumer registered for taskType: ${taskType}`)
    }

    // Framework-level idempotency (only when the idempotencyKey option is set)
    if (entry.idempotencyKey) {
      const key = entry.idempotencyKey(payload)
      const result = await this.executionLog.recordOnce(key, taskType)
      if (result === 'already-executed') {
        this.logger.log({
          message: '중복 수신 — ledger에 이미 기록, 스킵',
          task_type: taskType,
          idempotency_key: key
        })
        return   // return normally → the Consumer deletes the message
      }
    }

    const handler = this.moduleRef.get(entry.handlerClass, { strict: false })
    await (handler as Record<string, (p: object) => Promise<void>>)[entry.method](payload)
  }
}
```

- **The ledger is recorded right before dispatch (record-before-execute)**: for a Task with `idempotencyKey` set, an insert into the ledger is attempted **before the handler is called**. Even if the handler subsequently fails, the ledger entry remains, so a retry gets skipped as `already-executed`. If you need atomicity between handler success/failure and the ledger, see [Strong Atomicity (Level 3)](#strong-atomicity-level-3--a-rare-case).
- **Tasks that don't need the ledger**: a Task that is inherently idempotent (e.g. the `cleanup-expired` batch, which only "archives records that are already in an expired state," so running it multiple times produces the same result) doesn't need `idempotencyKey`. This saves the ledger-table cost.

## `TaskQueueConsumer` — SQS Polling

Receives messages from SQS and delegates to `TaskConsumerRegistry`. A shared Infrastructure component with no domain knowledge.

```typescript
// src/task-queue/task-queue-consumer.ts
import { Injectable, Logger, OnApplicationShutdown, OnModuleInit } from '@nestjs/common'
import {
  ChangeMessageVisibilityCommand,
  DeleteMessageCommand,
  ReceiveMessageCommand,
  SQSClient
} from '@aws-sdk/client-sqs'

import { HeartbeatConfig } from './task-consumer.decorator'
import { TaskConsumerRegistry } from './task-consumer-registry'

@Injectable()
export class TaskQueueConsumer implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(TaskQueueConsumer.name)
  private readonly sqs = new SQSClient({
    ...(process.env.AWS_ENDPOINT_URL ? { endpoint: process.env.AWS_ENDPOINT_URL } : {})
  })
  private readonly queueUrl = process.env.SQS_TASK_QUEUE_URL!
  private running = true
  private pollPromise: Promise<void> = Promise.resolve()

  constructor(private readonly registry: TaskConsumerRegistry) {}

  public onModuleInit(): void {
    this.pollPromise = this.poll()
  }

  public async onApplicationShutdown(): Promise<void> {
    this.running = false
    await this.pollPromise  // wait for in-flight Task processing to finish
  }

  private async poll(): Promise<void> {
    while (this.running) {
      try {
        const result = await this.sqs.send(new ReceiveMessageCommand({
          QueueUrl: this.queueUrl,
          MaxNumberOfMessages: 10,  // batch receive to improve throughput (max 10)
          WaitTimeSeconds: 20,      // long polling — reduces SQS API call cost
          VisibilityTimeout: 300    // set generously above the longest Task's max processing time
        }))

        for (const message of result.Messages ?? []) {
          const messageId = message.MessageId
          try {
            const { taskType, payload } = JSON.parse(message.Body ?? '{}')
            this.logger.log({ message: 'Task 시작', message_id: messageId, task_type: taskType })

            const heartbeat = this.registry.getHeartbeat(taskType)
            const run = (): Promise<void> => this.registry.dispatch(taskType, payload ?? {})

            if (heartbeat) {
              await this.withHeartbeat(message.ReceiptHandle!, heartbeat, run)
            } else {
              await run()
            }

            await this.sqs.send(new DeleteMessageCommand({
              QueueUrl: this.queueUrl,
              ReceiptHandle: message.ReceiptHandle!
            }))
            this.logger.log({ message: 'Task 완료', message_id: messageId, task_type: taskType })
          } catch (error) {
            this.logger.error({
              message: 'Task 실패 — visibility timeout 경과 후 재수신',
              message_id: messageId,
              error
            })
            // don't delete → gets re-received; moves to DLQ once maxReceiveCount is exceeded
          }
        }
      } catch (error) {
        this.logger.error({ message: 'SQS 수신 실패', error })
        await new Promise((resolve) => setTimeout(resolve, 3000))
      }
    }
  }

  private async withHeartbeat(
    receiptHandle: string,
    config: HeartbeatConfig,
    task: () => Promise<void>
  ): Promise<void> {
    const timer = setInterval(() => {
      void this.sqs.send(new ChangeMessageVisibilityCommand({
        QueueUrl: this.queueUrl,
        ReceiptHandle: receiptHandle,
        VisibilityTimeout: config.extendSeconds
      })).catch((error) => this.logger.warn({ message: '하트비트 실패', error }))
    }, config.intervalMs)

    try {
      await task()
    } finally {
      clearInterval(timer)
    }
  }
}
```

- **Message deletion only on success**: if an exception occurs, the message isn't deleted, so it's automatically re-received after the visibility timeout elapses → moves to the DLQ once `maxReceiveCount` is exceeded.
- **Graceful Shutdown awaits `pollPromise`**: if `onApplicationShutdown` doesn't wait for the loop to end, NestJS will terminate the app and cut off an in-flight Task midway. As in the implementation above, you must store `pollPromise` and await it during shutdown. If shutdown delay is a concern, apply the [Long-running Tasks and VisibilityTimeout Heartbeats](#long-running-tasks-and-visibilitytimeout-heartbeats) pattern below for long-running Tasks.
- **`MaxNumberOfMessages` can batch-receive up to 10**: leaving it at 1 means one round trip per message, which is low throughput. Parallelism is tuned via instance count × batch size.
- Set `VisibilityTimeout` generously above the longest Task's max processing time.
- **The Task Controller is in NestJS's default Singleton scope**: the current Consumer implementation processes messages within a batch sequentially in a `for` loop, so a single Task Controller instance handles only one message at a time. To prepare for a future switch to parallel dispatch, **don't keep shared mutable state (accumulating instance fields, static variables, etc.) in Task Controller methods**. Same statelessness principle as the HTTP Controller.

## `TaskController` — Executing Commands with `@TaskConsumer` Methods (Interface Layer)

**The Task Controller is an input adapter in the Interface layer.** Just as the HTTP Controller declares HTTP entry points with `@Get`/`@Post`, the Task Controller declares Task entry points with `@TaskConsumer('taskType')`. It injects the CommandService and executes the Command corresponding to the Task.

```typescript
// src/order/interface/order-task-controller.ts
import { Injectable, Logger } from '@nestjs/common'

import { ArchiveOrderCommand } from '@/order/application/command/archive-order-command'
import { OrderCommandService } from '@/order/application/command/order-command-service'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

@Injectable()
export class OrderTaskController {
  private readonly logger = new Logger(OrderTaskController.name)

  constructor(private readonly orderCommandService: OrderCommandService) {}

  // an inherently idempotent Task — no idempotencyKey needed
  @TaskConsumer('order.cleanup-expired')
  public async cleanupExpired(): Promise<void> {
    const count = await this.orderCommandService.cleanupExpiredOrders()
    this.logger.log({ message: '만료 주문 정리', cleaned_count: count })
  }

  // a Task that needs protection against per-entity duplicate execution — idempotencyKey specified
  @TaskConsumer('order.archive', {
    idempotencyKey: (payload: ArchiveOrderCommand) => `order.archive-${payload.orderId}`
  })
  public async archive(payload: ArchiveOrderCommand): Promise<void> {
    await this.orderCommandService.archiveOrder(payload)
  }
}
```

- **Delegates the Command with no logic**: the Task Controller only calls the CommandService's Command method. Don't put conditional branching or business rules here. Same role as the HTTP Controller.
- **Idempotency is a decorator option**: don't write ledger code directly inside the Task Controller. Specifying `@TaskConsumer`'s `idempotencyKey` option makes `TaskConsumerRegistry` block duplicates via `TaskExecutionLog` before dispatch.
- **No direct DB injection**: don't inject `DataSource`/`Repository<Entity>` into the Task Controller. The Interface layer depends only on the CommandService. Shared concerns (ledger, heartbeat) are handled by the task-queue framework.
- **The payload type is stated in the method signature**: makes the calling contract clear. Add runtime validation with class-validator if needed (see [Payload Validation](#payload-validation) below).
- **Apply the Interface DTO rule**: use the Application's Command class directly as the payload type, or `extends` it as an Interface DTO thin wrapper if needed. (Same approach as the HTTP RequestBody — see [layer-architecture.md](./layer-architecture.md#interface-dto).)
- **Error handling differs from the HTTP Controller**: the HTTP Controller's `.catch(error => { logger.error; throw generateErrorResponse(...) })` pattern is **not** used. The Task Controller **throws the exception upward as-is** — `TaskQueueConsumer` catches it and doesn't delete the message, following the visibility-timeout re-receive/retry → DLQ path. Wrapping it in `.catch` swallows the exception, causing the message to be deleted normally and the failure to be lost.
- **In a `@nestjs/cqrs`-style domain, inject the `CommandBus` directly**: the example above is for a Service-style domain. If the domain is already CommandHandler-based (see [cqrs-pattern.md](cqrs-pattern.md)), the Task Controller likewise injects the `CommandBus`, just like the HTTP Controller, and calls `commandBus.execute(new XxxCommand(...))` — don't create a separate `CommandService` wrapper whose only job is delegating to CommandBus solely for the Task Controller's sake. Don't mix both styles within one domain.

```typescript
// ❌ Don't imitate the HTTP Controller pattern — the failure gets lost
@TaskConsumer('order.notify')
public async notify(payload: NotifyOrderCommand): Promise<void> {
  return this.orderCommandService.notify(payload).catch((error) => {
    this.logger.error(error)
    throw generateErrorResponse(...)   // an HttpException — meaningless in a Task context
  })
}

// ✅ Just call it and let the exception propagate up — TaskQueueConsumer handles it
@TaskConsumer('order.notify')
public async notify(payload: NotifyOrderCommand): Promise<void> {
  await this.orderCommandService.notify(payload)
}
```

## `TaskQueue` — Outbox-based Implementation

### Why Outbox

In the Command Service, the DB change and the Task enqueue **must be bound atomically**. Calling SQS `SendMessage` directly inside the Command transaction causes a dual-write problem — a state of inconsistency where the SQS send succeeds but the DB gets rolled back, or the DB commits but the SQS send fails. For the same reason the Domain Event goes through an Outbox table, **the Task is likewise unified onto the path of writing to the `task_outbox` table → `TaskOutboxRelay` publishing it**.

The Scheduler (Cron) uses the same path too. Enqueuing at Cron time isn't within a transaction context, but since it's a single row insert, it's naturally atomic, and unifying the path keeps operations simple.

### `TaskQueue` interface

```typescript
// src/task-queue/task-queue.ts
export type EnqueueOptions = {
  groupId: string
  deduplicationId: string
  delaySeconds?: number  // up to 900 seconds of delay possible
}

export abstract class TaskQueue {
  abstract enqueue(taskType: string, payload: object, options: EnqueueOptions): Promise<void>
}
```

### `task_outbox` Entity

```typescript
// src/task-queue/task-outbox.entity.ts
import { Column, Entity, Index, PrimaryGeneratedColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('task_outbox')
@Index(['processed', 'createdAt'])
export class TaskOutboxEntity extends BaseEntity {
  @PrimaryGeneratedColumn('uuid')
  taskId: string

  @Column()
  taskType: string

  @Column('jsonb')
  payload: object

  @Column()
  groupId: string

  @Column()
  deduplicationId: string

  @Column('int', { nullable: true })
  delaySeconds: number | null

  @Column({ default: false })
  processed: boolean
}
```

### Outbox-based `TaskQueue` implementation

Injects `TransactionManager` and participates in the current transaction context. When called outside a transaction (e.g. from the Scheduler), it's inserted as a single row via the default EntityManager.

```typescript
// src/task-queue/task-queue-outbox.ts
import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'

import { EnqueueOptions, TaskQueue } from './task-queue'
import { TaskOutboxEntity } from './task-outbox.entity'

@Injectable()
export class TaskQueueOutbox extends TaskQueue {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async enqueue(taskType: string, payload: object, options: EnqueueOptions): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(TaskOutboxEntity, {
      taskType,
      payload,
      groupId: options.groupId,
      deduplicationId: options.deduplicationId,
      delaySeconds: options.delaySeconds ?? null,
      processed: false
    })
  }
}
```

### `TaskOutboxRelay` — Publishing Outbox → SQS

Polls `task_outbox` on a short cycle and sends unpublished rows to SQS. The same pattern as the Domain Event's `OutboxPoller`.

```typescript
// src/task-queue/task-outbox-relay.ts
import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'
import { SendMessageCommand, SQSClient } from '@aws-sdk/client-sqs'
import { DataSource, LessThan } from 'typeorm'

import { TaskOutboxEntity } from './task-outbox.entity'

@Injectable()
export class TaskOutboxRelay {
  private readonly logger = new Logger(TaskOutboxRelay.name)
  private readonly sqs = new SQSClient({
    ...(process.env.AWS_ENDPOINT_URL ? { endpoint: process.env.AWS_ENDPOINT_URL } : {})
  })
  private readonly queueUrl = process.env.SQS_TASK_QUEUE_URL!

  constructor(private readonly dataSource: DataSource) {}

  @Cron('*/3 * * * * *')  // poll every 3 seconds
  public async relay(): Promise<void> {
    const repo = this.dataSource.getRepository(TaskOutboxEntity)
    const rows = await repo.find({
      where: { processed: false },
      order: { createdAt: 'ASC' },
      take: 100
    })

    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: this.queueUrl,
          MessageBody: JSON.stringify({ taskType: row.taskType, payload: row.payload }),
          MessageGroupId: row.groupId,
          MessageDeduplicationId: row.deduplicationId,
          ...(row.delaySeconds !== null ? { DelaySeconds: row.delaySeconds } : {})
        }))
        await repo.update({ taskId: row.taskId }, { processed: true })
      } catch (error) {
        this.logger.error({ message: 'SQS 발행 실패', task_id: row.taskId, error })
      }
    }
  }

  @Cron('0 3 * * *')  // every day at 03:00 — clean up rows that have been published
  public async cleanup(): Promise<void> {
    const repo = this.dataSource.getRepository(TaskOutboxEntity)
    const threshold = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
    await repo.delete({ processed: true, createdAt: LessThan(threshold) })
  }
}
```

- **Duplicate enqueuing across multiple instances**: even if the Cron fires simultaneously on multiple instances, as long as `deduplicationId` is the same, SQS FIFO's 5-minute dedup window ensures only one message actually enters the queue. Multiple rows may be created in `task_outbox`, but even if the Relay sends each one, they get filtered out at the SQS level.
- **Publish failures are retried on the next poll**: since the `processed` flag wasn't flipped, it's naturally reprocessed.

### Relay multi-instance race — limitations and mitigations

The implementation above means that when multiple app instances run the Relay concurrently, **they can both pick up the same row and each send it to SQS**. Usually SQS FIFO's 5-minute dedup window prevents this, but the window can be exceeded and duplicate delivery can occur in the following situations.

- The Relay was down for over 5 minutes due to an outage and then recovered (sending accumulated rows in a batch → a duplicate outside the 5-minute window due to timing)
- An old outbox row with the same `deduplicationId` was left lingering on another instance and gets sent late

Mitigation strategies — choose based on your situation:

| Method | Description | Trade-off |
|------|------|--------------|
| **Consumer-side idempotency** (default) | The last line of defense. Apply the ledger pattern from the [Idempotency](#idempotency) section above to every important Task | Always required |
| **`SELECT ... FOR UPDATE SKIP LOCKED`** | The Relay atomically claims a row — when running concurrently, only one instance processes a given row | Slightly more code complexity |
| **Leader election** | Run the Relay on only a single instance (e.g. via a Redis distributed lock) | SPOF risk, extra infrastructure |

**Consumer idempotency is non-negotiable.** Even if you lock the Relay, at-least-once delivery is inherent to SQS, so the Consumer must always be idempotent.

### `deduplicationId` UNIQUE constraint — an option

Putting a DB UNIQUE constraint on `task_outbox.deduplicationId` means **only one of the multi-instance outbox writes succeeds at write time** (the rest fail with a unique violation → ignored). This blocks `task_outbox` row explosion at the DB level.

**Downside**: a once-used `deduplicationId` can **never be reused**. For example, if an entity-based dedupId like `order.archive-<orderId>` ever needs to be intentionally re-run later, this blocks it. Date-based ones (`cleanup-2026-04-18`) are naturally unique over time, so they're safe.

→ **Default to operating without the UNIQUE constraint** (delegating to SQS dedup), and only apply it narrowly via a partial UNIQUE index (`WHERE task_type = 'xxx'`) if you're sure a specific taskType uses a date-based dedupId.

DI binding is done via `{ provide: TaskQueue, useClass: TaskQueueOutbox }` in the [Module Registration](#module-registration) section.

## Scheduler — Cron → TaskQueue

The `@Cron` handler only calls `TaskQueue.enqueue`. It doesn't execute business logic directly.

```typescript
// src/order/infrastructure/order-cleanup-scheduler.ts
import { Injectable, Logger } from '@nestjs/common'
import { Cron, CronExpression } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

@Injectable()
export class OrderCleanupScheduler {
  private readonly logger = new Logger(OrderCleanupScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  @Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
  public async enqueueDailyCleanup(): Promise<void> {
    const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
    try {
      await this.taskQueue.enqueue(
        'order.cleanup-expired',
        {},
        { groupId: 'order.cleanup', deduplicationId: dedupId }
      )
      this.logger.log({ message: '만료 주문 정리 Task 적재', dedup_id: dedupId })
    } catch (error) {
      // @nestjs/schedule silently swallows exceptions from Cron handlers, so log explicitly
      this.logger.error({ message: '만료 주문 정리 Task 적재 실패', dedup_id: dedupId, error })
    }
  }
}
```

- **Fixing `MessageDeduplicationId` to a date unit**: even if multiple instances enqueue at the same Cron timing, only one message enters the queue within the 5-minute dedup window. This is the key to resolving duplicate Cron execution.
- **`@nestjs/schedule` silences exceptions**: exceptions inside a Cron handler are swallowed without logging. try-catch + `logger.error` is required to **guarantee explicit visibility**.
- **A Scheduler with multiple `@Cron` methods repeats try-catch per method**: one method's exception doesn't affect another method, but every `@Cron` method needs the same try-catch + logger.error block to guarantee its own failure visibility. If there's a lot of repetition, extract it into a private helper like the one below.

```typescript
// inside the Scheduler class
private async runSafely(name: string, fn: () => Promise<void>): Promise<void> {
  try {
    await fn()
  } catch (error) {
    this.logger.error({ message: `Cron 실패: ${name}`, error })
  }
}

@Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
public async enqueueDailyCleanup(): Promise<void> {
  await this.runSafely('order.cleanup-expired', async () => {
    const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
    await this.taskQueue.enqueue(
      'order.cleanup-expired', {},
      { groupId: 'order.cleanup', deduplicationId: dedupId }
    )
  })
}
```

- **The next Cron tick recovers from a failure**: since the date-based `deduplicationId` is naturally unique, even if it's re-enqueued on the next tick after a failure, no duplicate row builds up in `task_outbox` and no duplicate delivery occurs on SQS. (If the outbox write itself fails due to a DB outage, either wait for the next tick after the outage clears, or trigger it manually as an emergency measure.)

## Module Registration

Register a single shared Task Queue module, and register the Task Controller in each domain module.

```typescript
// src/task-queue/task-queue-module.ts
import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { TaskConsumerRegistry } from './task-consumer-registry'
import { TaskExecutionLog } from './task-execution-log'
import { TaskExecutionLogDb } from './task-execution-log-db'
import { TaskExecutionLogCleaner } from './task-execution-log-cleaner'
import { TaskExecutionLogEntity } from './task-execution-log.entity'
import { TaskOutboxEntity } from './task-outbox.entity'
import { TaskOutboxRelay } from './task-outbox-relay'
import { TaskQueue } from './task-queue'
import { TaskQueueConsumer } from './task-queue-consumer'
import { TaskQueueOutbox } from './task-queue-outbox'

@Global()
@Module({
  imports: [TypeOrmModule.forFeature([TaskOutboxEntity, TaskExecutionLogEntity])],
  providers: [
    TaskConsumerRegistry,
    TaskQueueConsumer,
    TaskOutboxRelay,
    TaskExecutionLogCleaner,
    { provide: TaskQueue, useClass: TaskQueueOutbox },
    { provide: TaskExecutionLog, useClass: TaskExecutionLogDb }
  ],
  exports: [TaskQueue, TaskExecutionLog]
})
export class TaskQueueModule {}
```

```typescript
// src/order/order-module.ts
@Module({
  controllers: [OrderController],           // HTTP entry point
  providers: [
    OrderCommandService,
    OrderTaskController,                    // Task entry point — registered in providers
    OrderCleanupScheduler,                  // enqueues a Task via Cron
    { provide: OrderRepository, useClass: OrderRepositoryImpl }
  ]
})
export class OrderModule {}
```

- Register the HTTP Controller in `controllers`, and the Task Controller in `providers`. Since NestJS's `controllers` array is meant for route mapping, an SQS-based Task Controller must be registered in `providers` so `ModuleRef.get()` can resolve an instance. Even if the decorator accumulates metadata in a Map, it won't run unless there's an instance in the DI container.

## Ad-hoc Task Enqueuing (Inside a Transaction)

When the Application Service enqueues a Task alongside a DB change, call `taskQueue.enqueue` **inside the same transaction**. Since the Outbox implementation (`TaskQueueOutbox`) uses `TransactionManager`'s current manager, the Command's DB change and the Task enqueue are bound into the same transaction. The `task_outbox` row only remains if the commit succeeds, and it disappears together on rollback — **this eliminates the dual-write problem at the source**. See [Outbox-based `TaskQueue` Implementation](#taskqueue--outbox-based-implementation) for a code-level explanation of the participation mechanism.

```typescript
import { TaskQueue } from '@/task-queue/task-queue'  // abstract class

@Injectable()
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager,
    private readonly taskQueue: TaskQueue
  ) {}

  public async cancelOrder(orderId: string): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(/* ... */)

    order.cancel('user request')

    // DB change + Task enqueue happen atomically inside the same transaction
    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)
      await this.taskQueue.enqueue(
        'order.archive',
        { orderId },
        { groupId: orderId, deduplicationId: `order.archive-${orderId}` }
      )
    })
  }
}
```

- **Safe to call outside a transaction too**: when called from somewhere with no transaction context, like the Scheduler, it operates as a single row insert. Since the path is unified, the producer doesn't need to worry about context.
- **The actual SQS send is performed by `TaskOutboxRelay`**: it polls committed rows every 3 seconds and publishes them. The Application doesn't need to know whether the publish succeeded or failed.

## MessageGroupId Strategy

In a FIFO queue, **messages sharing the same `MessageGroupId` are processed strictly in order**. Getting this wrong causes either unintended serialization that drops throughput, or the opposite — broken ordering.

| Situation | groupId setting |
|------|-------------|
| Global Cron batch (once a day, etc.) | Based on Task category: `'order.cleanup'` — only needs to run on a single instance |
| Sequential ordering needed per Aggregate | Aggregate ID: `orderId` — follow-up Tasks for the same order stay in order |
| Order doesn't matter + high throughput | Random UUID or `taskType`+random: maximizes parallelism |

**Key principle**: groupId is **the boundary of parallelism**. The same group is serial, different groups are parallel. Put only the minimum necessary level of ordering into groupId.

## Idempotency

Since SQS guarantees **at-least-once delivery**, the Command called by the `@TaskConsumer` method **must be idempotent**. There are 3 tiers of idempotency mechanisms.

> **Note**: the 3-tier model described here (inherent idempotency / framework ledger / strong atomicity) applies **not just to Tasks but equally to Domain Event EventHandlers**. `@HandleEvent` handlers also assume at-least-once, so the same ledger strategy is valid for handlers with significant side effects. See [domain-events.md — Event Handler Idempotency](./domain-events.md#event-handler-idempotency) for a brief example on the Domain Event side.

### Inherent idempotency (Level 1 · default)

No extra mechanism is needed if the Command itself produces the same result even when run repeatedly. A Cron batch (state-based filtering + overwriting with a final state) is the typical example.

```typescript
public async cleanupExpiredOrders(): Promise<number> {
  const { orders } = await this.orderRepository.findOrders({
    status: ['expired'], take: 100, page: 0
  })
  for (const order of orders) {
    order.archive()                 // if already archived, ignored internally
    await this.orderRepository.saveOrder(order)
  }
  return orders.length
}
```

→ The Task Controller is `@TaskConsumer('order.cleanup-expired')` — **no options**. The lightest tier.

### Framework-level ledger (Level 2 · generally recommended)

For a Task that must block per-entity duplicate execution (side-effecting work such as re-charging or calling an external API), use the **`idempotencyKey` option of `@TaskConsumer`** to leave a ledger entry in `TaskExecutionLog`. `TaskConsumerRegistry` attempts to insert into the ledger **right before** dispatch, and if an entry already exists, it returns `'already-executed'` → the method call is skipped → the Consumer deletes the message normally.

```typescript
@TaskConsumer('order.archive', {
  idempotencyKey: (payload: ArchiveOrderCommand) => `order.archive-${payload.orderId}`
})
public async archive(payload: ArchiveOrderCommand): Promise<void> {
  await this.orderCommandService.archiveOrder(payload)
}
```

- Declare the payload type as the Application's Command class (`ArchiveOrderCommand`) to make the calling contract explicit. Pass the Command object through as-is when calling the Service — the same pattern as the HTTP Controller's `new CommandClass(body)` → Service call.
- **The payload is only a type hint — at runtime it's a plain object**: since it's the result of `JSON.parse`ing the SQS message body, **you cannot use the Command class's instance methods (getters, `equals()`, etc.)**. Only field access is possible. If you need to call methods or validate with validator decorators, apply the `plainToInstance(Command, payload)` pattern in [Payload Validation](#payload-validation).
- The Task Controller code stays as concise as tier 1. The framework handles the ledger logic.
- **The semantics are "record-before-execute"**: even if the handler fails, the ledger entry remains, so a retry gets skipped. In other words, **"remembered as attempted once, regardless of success"**. Sufficient for most practical cases.
- **Exceptions in the `idempotencyKey` function itself**: if it throws while generating the key, the exception propagates from dispatch → the message isn't deleted → re-received → DLQ. Keep the key-generation logic simple, accessing only payload fields.

### Strong atomicity (Level 3 · a rare case)

If you need the strict atomicity guarantee that "the ledger entry only persists if the handler succeeds" (a rare case), have the Task Controller inject `TaskExecutionLog` **directly and call it inside `transactionManager.run`**. Do **not** specify the framework's `idempotencyKey` in this case (specifying it would double-check).

```typescript
@Injectable()
export class OrderChargeTaskController {
  constructor(
    private readonly orderCommandService: OrderCommandService,
    private readonly transactionManager: TransactionManager,
    private readonly executionLog: TaskExecutionLog
  ) {}

  @TaskConsumer('order.charge')
  public async charge(payload: ChargeOrderCommand): Promise<void> {
    await this.transactionManager.run(async () => {
      const result = await this.executionLog.recordOnce(`order.charge-${payload.orderId}`)
      if (result === 'already-executed') return
      await this.orderCommandService.chargeOrder(payload)
    })
  }
}
```

- **The mechanism by which the ledger and the Command participate in the same transaction**: because `TaskExecutionLogDb.recordOnce()` internally uses `TransactionManager.getManager()`, it **automatically participates** in the transaction context opened by the outer `transactionManager.run(...)`. If the Command fails and rolls back, the ledger insert rolls back with it, so a retry is processed correctly again (true "exactly-once-on-success").
- **Transaction safety on duplicate receipt**: because `recordOnce()` uses `INSERT ... ON CONFLICT DO NOTHING`, the transaction doesn't abort even when it returns `'already-executed'`. It early-exits via `return`, and the outer `transactionManager.run` commits as-is (a no-op commit). See the note under [`TaskExecutionLog` Interface + Implementation](#taskexecutionlog-interface--implementation) for why a try/catch-unique-violation approach is risky here.
- **`recordOnce`'s 2nd argument (taskType) is optional**: it's just for logging, and is only passed when called via the Registry's framework path. It can be omitted when called directly from a Task Controller (see the example) — avoiding redundantly re-stating information the decorator already has.
- Downside: the Task Controller injects `TaskExecutionLog` + `TransactionManager` directly, adding more code. Use tier 3 only in a limited way, for Tasks involving money such as payments or external transactions.

### Entity / Helpers (Framework Internals)

**Internal infrastructure code** of the task-queue module (Entity, Relay, Cleaner, etc.) may inject/use `DataSource` directly. The "no direct DB access from the Task Controller" principle applies only to the domain Interface layer — the task-queue framework itself is a technical component that directly handles DB, Cron, and SQS infrastructure.

```typescript
// src/task-queue/task-execution-log.entity.ts
import { Column, Entity, Index, PrimaryColumn } from 'typeorm'

// the ledger is suited only for hard delete, so it doesn't extend BaseEntity (which includes softDelete)
@Entity('task_execution_log')
@Index(['executedAt'])
export class TaskExecutionLogEntity {
  @PrimaryColumn()
  taskId: string

  @Column({ nullable: true })
  taskType: string | null   // for logging purposes — null if absent

  @Column()
  executedAt: Date
}
```

```typescript
// src/common/is-unique-violation.ts
import { QueryFailedError } from 'typeorm'

// Postgres unique_violation = SQLSTATE 23505
export function isUniqueViolation(error: unknown): boolean {
  return (
    error instanceof QueryFailedError
    && (error.driverError as { code?: string } | undefined)?.code === '23505'
  )
}
```

### `TaskExecutionLog` Interface + Implementation

Injected and used by `TaskConsumerRegistry` (tier 2), and in rare cases may also be injected directly by a Task Controller (tier 3). The `taskType` parameter is used only for logging, so it's optional.

```typescript
// src/task-queue/task-execution-log.ts
export type RecordResult = 'recorded' | 'already-executed'

export abstract class TaskExecutionLog {
  abstract recordOnce(taskId: string, taskType?: string): Promise<RecordResult>
}
```

```typescript
// src/task-queue/task-execution-log-db.ts
import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'

import { RecordResult, TaskExecutionLog } from './task-execution-log'
import { TaskExecutionLogEntity } from './task-execution-log.entity'

@Injectable()
export class TaskExecutionLogDb extends TaskExecutionLog {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async recordOnce(taskId: string, taskType?: string): Promise<RecordResult> {
    const manager = this.transactionManager.getManager()
    // INSERT ... ON CONFLICT DO NOTHING — used instead of a try/catch unique violation.
    // Because Postgres puts the whole transaction into an aborted state when an error
    // occurs within it, in the tier-3 (strong atomicity) pattern where an outer
    // transaction exists, a try/catch approach would make subsequent queries and the
    // commit fail with SQLSTATE 25P02.
    // `.orIgnore()` never throws an exception, so it's safe in any context.
    const result = await manager
      .createQueryBuilder()
      .insert()
      .into(TaskExecutionLogEntity)
      .values({ taskId, taskType: taskType ?? null, executedAt: new Date() })
      .orIgnore()
      .execute()
    return (result.identifiers?.length ?? 0) > 0 ? 'recorded' : 'already-executed'
  }
}
```

- **Why the UPSERT pattern was chosen**: the `try { INSERT } catch (isUniqueViolation) { ... }` approach isn't used — because in Postgres, a unique violation puts **the current transaction into an aborted state** (SQLSTATE 25P02). If `recordOnce()` is called inside `transactionManager.run(...)` as in the tier-3 strong-atomicity pattern, after returning `'already-executed'`, all subsequent work and the commit would fail with "current transaction is aborted." `.orIgnore()` (`ON CONFLICT DO NOTHING`) **doesn't throw an exception** and silently ignores the conflicting row, so it's safe in any transaction context.
- The `isUniqueViolation` helper is still kept because it remains useful outside the ledger (e.g. handling a `task_outbox.deduplicationId` UNIQUE violation).

### Ledger cleanup

`task_execution_log` grows indefinitely if left unattended. A retention period of **`maxReceiveCount × VisibilityTimeout` plus some margin** is enough (the maximum period during which the same message could be redelivered). A 30-day retention is usually generous enough.

```typescript
// src/task-queue/task-execution-log-cleaner.ts
import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'
import { DataSource, LessThan } from 'typeorm'

import { TaskExecutionLogEntity } from './task-execution-log.entity'

@Injectable()
export class TaskExecutionLogCleaner {
  private readonly logger = new Logger(TaskExecutionLogCleaner.name)

  constructor(private readonly dataSource: DataSource) {}

  @Cron('0 4 * * *')  // every day at 04:00
  public async cleanup(): Promise<void> {
    const threshold = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000)
    const result = await this.dataSource
      .getRepository(TaskExecutionLogEntity)
      .delete({ executedAt: LessThan(threshold) })
    this.logger.log({ message: 'ledger cleanup', deleted: result.affected ?? 0 })
  }
}
```

## Payload Validation

Since the input comes from an external source (SQS), **validate the payload schema** inside the Task Controller method. The same reasoning as why the HTTP Controller validates the RequestBody with `class-validator`.

Reusing the Application's Command class (which already has `class-validator` decorators attached) as the payload type shares the validation logic between HTTP and Task.

```typescript
import { plainToInstance } from 'class-transformer'
import { validateOrReject } from 'class-validator'

import { SendReminderEmailCommand } from '@/order/application/command/send-reminder-email-command'

@TaskConsumer('order.send-reminder-email')
public async sendReminderEmail(payload: object): Promise<void> {
  const command = plainToInstance(SendReminderEmailCommand, payload)
  await validateOrReject(command)   // if validation fails, throws → visibility timeout → retry → DLQ
  await this.orderCommandService.sendReminderEmail(command)
}
```

- A validation failure is also an exception, so the message isn't deleted and is retried. Since the same payload fails for the same reason every time, it moves to the DLQ once `maxReceiveCount` is exceeded. A structure where **poison payloads are naturally isolated**.

### Payload size limit (SQS 256KB)

A single SQS message is **256KB max**. Large payloads (large file contents, bulky JSON, etc.) shouldn't be sent as-is.

- **Put only small metadata in the payload**: something like `{ orderId: 'o1', itemIds: ['i1', 'i2'] }`.
- **Offload large data to S3** and put only the key: `{ orderId: 'o1', payloadS3Key: 'tasks/abc.json' }`. The Task Controller fetches it back from S3 to process it.
- The `jsonb` column of `task_outbox.payload` itself can hold larger values, but the moment the Relay publishes it to SQS, the 256KB limit applies. If the limit is exceeded, SendMessage fails and the row stays `processed=false`, failing repeatedly — such rows need manual cleanup, or add a size check + DLQ-move logic to the Relay.

### Combining validation + idempotency

Something to watch out for when applying payload validation together with framework-level idempotency (`idempotencyKey`): **the framework's ledger entry is recorded before the handler is called**. That is, even if the payload is invalid and the handler throws, the ledger has already been recorded, so on retry it's skipped as `already-executed` — meaning **an invalid payload may never get processed, forever**.

For Tasks where validation is essential, it's correct to operate either by **having the producer fully control the payload**, or by sending straight to the DLQ when validation fails (i.e. retrying is pointless).

```typescript
@TaskConsumer('order.dispatch-shipment', {
  idempotencyKey: (payload: DispatchShipmentCommand) => `order.dispatch-shipment-${payload.orderId}`
})
public async dispatchShipment(payload: object): Promise<void> {
  // validation runs after the ledger — this is only safe for a producer-controlled payload
  const command = plainToInstance(DispatchShipmentCommand, payload)
  await validateOrReject(command)
  await this.orderCommandService.dispatchShipment(command)
}
```

For cases that **need reprocessing after a validation failure** (e.g. an external system sends the payload), don't use `idempotencyKey`; use the [Strong Atomicity pattern (Level 3)](#strong-atomicity-level-3--a-rare-case) instead — directly control the validate → ledger → Command order inside the transaction.

## Long-running Tasks and VisibilityTimeout Heartbeats

`VisibilityTimeout` can go up to 12 hours, but a Task whose processing runs longer than that, or whose duration is unpredictable, needs to **periodically call `ChangeMessageVisibility` while processing** to extend the timeout. Without extending it, another Consumer will receive the same message as a duplicate.

`TaskQueueConsumer`'s [`withHeartbeat`](#taskqueueconsumer--sqs-polling) already implements this logic. **Just specify `heartbeat` in the `@TaskConsumer` option**, and the heartbeat automatically runs only while that taskType is being processed.

```typescript
@TaskConsumer('order.generate-large-report', {
  heartbeat: { intervalMs: 60_000, extendSeconds: 180 }  // extend by 180s every 60s
})
public async generateLargeReport(payload: { reportId: string }): Promise<void> {
  await this.orderCommandService.generateReport(payload.reportId)
}
```

- **Option design**: `intervalMs` should be `< extendSeconds * 1000`. Extending by 180 seconds every 60 seconds always leaves margin.
- **Default: no option specified (no heartbeat)**: most Tasks finish in seconds to tens of seconds, so the initial `VisibilityTimeout: 300` is enough.
- **Keep the initial `VisibilityTimeout` short + extend via heartbeat**: unconditionally setting a large initial value causes a large retry delay on a real failure. Setting it short and extending only the Tasks that need it via heartbeat is better for resilience.

## Graceful Shutdown

On app shutdown, `TaskQueueConsumer`'s polling loop must stop first. The `OnApplicationShutdown` in the implementation above handles this. In-progress Tasks are waited on until completion, and if they fail, another instance re-receives them after the visibility timeout. See [graceful-shutdown.md](./graceful-shutdown.md) for the detailed ordering.

**However, the `pollPromise` await is not an infinite wait.** A container orchestrator (K8s, etc.) forcibly terminates with SIGKILL if cleanup isn't finished within `terminationGracePeriodSeconds` (default 30s). If the Task Controller gets stuck (an infinite loop, a DB deadlock, etc.), shutdown blocks and gets force-killed, but since the in-flight message was never deleted, another instance re-receives it after the visibility timeout — meaning **at-least-once semantics recover from the forced shutdown**. Still, keep the following in mind.

- **Design so a Task's max processing time is smaller than the grace period**. If it runs longer, you need `@TaskConsumer heartbeat` together with a larger grace period.
- **Duplicate execution from re-receiving is possible even on the normal shutdown path** — Consumer-side idempotency is the defense here too.

## DLQ Monitoring

Messages piling up in the DLQ are **evidence of a code bug or a poison payload**. Watch `ApproximateNumberOfMessages > 0` with a CloudWatch alarm, and after fixing the root cause, redrive from the DLQ back to the original queue.

## Testing

The `@TaskConsumer` / `@Cron` decorators only **register metadata** and don't wrap the method call itself. So a unit test can bypass the decorator and call the method directly. SQS mocking is needed only at integration boundaries.

### Task Controller — unit test

Inject only a mocked CommandService and call the method directly. No queue/SQS/ledger needed — since the Task Controller is a pure delegation, only verify the business behavior.

```typescript
describe('OrderTaskController', () => {
  const orderCommandService = { archiveOrder: jest.fn() } as any
  const controller = new OrderTaskController(orderCommandService)

  test('archive passes the Command object to CommandService.archiveOrder', async () => {
    await controller.archive({ orderId: 'o1' })
    expect(orderCommandService.archiveOrder).toHaveBeenCalledWith({ orderId: 'o1' })
  })
})
```

> The idempotency ledger is handled at the `TaskConsumerRegistry` level, so it's not a concern for the Task Controller unit test. Verify ledger behavior in a `TaskConsumerRegistry` or `TaskExecutionLogDb` integration test.

### `TaskConsumerRegistry` — integration test

Verify that a Task with an `idempotencyKey` option actually records the ledger and gets skipped on duplicate calls.

```typescript
test('a Task with idempotencyKey is skipped on the second call', async () => {
  // assume OrderTaskController is already registered
  const controller = moduleRef.get(OrderTaskController)
  const spy = jest.spyOn(controller, 'archive')

  await registry.dispatch('order.archive', { orderId: 'o1' })
  await registry.dispatch('order.archive', { orderId: 'o1' })

  expect(spy).toHaveBeenCalledTimes(1)   // the second call is skipped via the ledger
})
```

### Scheduler — unit test

Inject a mocked `TaskQueue`, call the `@Cron` method directly, and verify the `enqueue` arguments.

```typescript
test('enqueues the expired-order cleanup Task with a date-based dedupId', async () => {
  const taskQueue = { enqueue: jest.fn() } as any
  const scheduler = new OrderCleanupScheduler(taskQueue)

  await scheduler.enqueueDailyCleanup()

  expect(taskQueue.enqueue).toHaveBeenCalledWith(
    'order.cleanup-expired',
    {},
    expect.objectContaining({ groupId: 'order.cleanup', deduplicationId: expect.stringMatching(/^order\.cleanup-expired-\d{4}-\d{2}-\d{2}$/) })
  )
})
```

### `TaskQueueOutbox` — integration test

Verify the `task_outbox` row insert and transaction-rollback behavior against a real DB.

```typescript
test('enqueue inserts a task_outbox row', async () => {
  await taskQueueOutbox.enqueue('order.archive', { orderId: 'o1' }, { groupId: 'o1', deduplicationId: 'd1' })
  const rows = await dataSource.getRepository(TaskOutboxEntity).find()
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ taskType: 'order.archive', processed: false })
})

test('the row also rolls back on transaction rollback', async () => {
  await expect(
    transactionManager.run(async () => {
      await taskQueueOutbox.enqueue('order.archive', { orderId: 'o1' }, { groupId: 'o1', deduplicationId: 'd2' })
      throw new Error('rollback')
    })
  ).rejects.toThrow()
  const rows = await dataSource.getRepository(TaskOutboxEntity).findBy({ deduplicationId: 'd2' })
  expect(rows).toHaveLength(0)
})
```

### `TaskOutboxRelay` / `TaskQueueConsumer` — integration test

- **Relay**: mock-replace the SQSClient, or send for real via LocalStack. Verify that it flips to `processed=true`.
- **Consumer**: send a message directly to a LocalStack queue via `SendMessage`, then verify that the Task Controller's `@TaskConsumer` method gets called.

See [testing.md](./testing.md) for shared patterns (TestContainer, transaction rollback, etc.).

## Interval / Timeout

Prefer expressing even simple repetition or delayed execution through the Task Queue. Use `@Interval` / `@Timeout` sparingly, and only for **process-local work** (e.g. warming an in-memory cache).

```typescript
@Timeout(5000)  // once, 5 seconds after app startup — process-local cache warming
async warmupCache() { /* ... */ }
```

`@Interval` is likewise restricted to the Infrastructure layer for the same reason as `@Cron` —
`harness/evaluators/rules/scheduler.evaluator.ts` covers `@Interval` usage as well as `@Cron`
(caught under the same ruleIds, e.g. `scheduler.layer`/`scheduler.cron.try-catch`).

## Principles

- **Subscribe to a Task via the `@TaskConsumer` decorator**: a single `taskType` string connects the Scheduler (enqueue) and the Task Controller (consume).
- **The Task Controller is in the Interface layer**: the same kind of input adapter as the HTTP Controller. It injects `CommandService` and only executes the Command. No conditional branching or business logic.
- **The Task Controller throws errors as-is**: the HTTP Controller's `.catch + generateErrorResponse` pattern is prohibited. Exceptions are caught by `TaskQueueConsumer`, which delegates to retry/DLQ.
- **Enqueuing goes through the Outbox**: `TaskQueue.enqueue` writes a row to `task_outbox`, and `TaskOutboxRelay` publishes it to SQS. Atomicity between the Command transaction and the Task enqueue is guaranteed.
- **The Scheduler only enqueues**: the `@Cron` handler only calls `TaskQueue.enqueue`. The Scheduler and the SQS-polling infrastructure are in the Infrastructure layer.
- **Domain/Application don't know the queue implementation**: the Application Service depends only on the **abstract class** `TaskQueue`. The SQS/Outbox implementation is injected via DI binding.
- **Task ≠ Domain Event**: a Task is an asynchronous job implemented and run as needed (1:1); a Domain Event is a means of receiving and processing the result of a Command execution (1:N). See the table above for the selection criteria.
- **`taskType` is globally unique**: duplicate `@TaskConsumer` registration fails at bootstrap. (Unlike the Domain Event's 1:N fan-out.)
- **A single Task queue**: Tasks from every domain share one SQS FIFO queue. Routing is done via the `taskType` string.
- **FIFO + MessageDeduplicationId**: duplicate Cron enqueuing from multiple instances is prevented at the queue level.
- **Never delete a message on failure**: trust the visibility-timeout → re-receive → DLQ structure. Swallowing it with try-catch and deleting loses the failure.
- **Long Tasks use the `@TaskConsumer` heartbeat option**: keep the initial `VisibilityTimeout` short, and specify `heartbeat` only for the taskTypes that need it, extending it during processing.
- **Commands must be idempotent**: since delivery is at-least-once, the result must be the same even if the same Task runs 2+ times. 3-tier strategy: ① inherent idempotency ② `@TaskConsumer({ idempotencyKey })` framework ledger ③ if strong atomicity is needed, inject `TaskExecutionLog` directly in the Task Controller.
- **Don't write ledger code in the Task Controller (by default)**: the framework handles it via the `idempotencyKey` option. The Task Controller is left with only the CommandService call.
- **The Task Controller must not access the DB directly**: don't inject `DataSource`/`Repository<Entity>`. Shared concerns (ledger, heartbeat) are handled by the task-queue framework.
- **Both the ledger and the outbox require a cleanup Cron**: `task_outbox` / `task_execution_log` grow indefinitely if left unattended.
- **The Scheduler requires try-catch + logger.error**: since `@nestjs/schedule` swallows exceptions, failures become unobservable without explicit logging.
- **Consumer idempotency is the last line of defense**: whether it's a Relay multi-instance race, a forced shutdown during Graceful Shutdown, or a re-receive after visibility timeout expiry, the system recovers in every case as long as the Consumer is idempotent.
- **A DLQ is mandatory**: configure a DLQ for every Task queue and watch it with a CloudWatch alarm.
