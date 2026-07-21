import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { OutboxModule } from '@/outbox/outbox-module'

import { TaskConsumerRegistry } from './task-consumer-registry'
import { TaskOutboxEntity } from './task-outbox.entity'
import { TaskOutboxRelay } from './task-outbox-relay'
import { TaskQueue } from './task-queue'
import { TaskQueueConsumer } from './task-queue-consumer'
import { TaskQueueOutbox } from './task-queue-outbox'

// 공유 Task Queue 인프라 모듈(outbox/outbox-module.ts와 같은 모양) — task_outbox
// 테이블 하나 + Relay 하나 + Consumer 하나 + 레지스트리 하나를 갖는다. @Global이지만
// 활성화를 위해 AppModule의 imports에 한 번은 포함되어야 한다
// (docs/architecture/scheduling.md#appmodule-설정).
//
// OutboxModule을 import하는 이유는 SQS_CLIENT(같은 SDK 커넥션)를 재사용하기 위함이다 —
// OutboxModule이 이미 @Global이라 명시적 import 없이도 주입은 되지만, 이 모듈이
// SQS_CLIENT에 실제로 의존하고 있음을 코드에서 드러내기 위해 명시한다.
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
