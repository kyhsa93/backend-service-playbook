import { Global, Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxConsumer } from '@/outbox/outbox-consumer'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxPoller } from '@/outbox/outbox-poller'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { SqsClientProvider } from '@/outbox/sqs-client-provider'

// Account/Payment가 각자 별도의 OutboxRelay를 두던 구조를 걷어내고, 이 하나의 전역
// 모듈이 outbox 테이블 하나 + Poller 하나 + Consumer 하나 + 레지스트리 하나를 갖는다
// (Go/Java/Kotlin/FastAPI 구현체와 동일한 "단일 공유 outbox" 컨벤션). OutboxPoller/
// OutboxConsumer는 여기서만 provider로 등록되며 다른 모듈에 export하지 않는다 —
// Command Handler 등 어떤 코드도 이들을 직접 주입받아 호출할 수 없어야 한다
// (docs/architecture/domain-events.md — 동기 드레인 금지).
@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter, EventHandlerRegistry, SqsClientProvider, OutboxPoller, OutboxConsumer],
  exports: [TransactionManager, OutboxWriter, EventHandlerRegistry]
})
export class OutboxModule {}
