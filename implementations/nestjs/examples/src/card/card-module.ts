import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { CancelCardsByAccountCommandHandler } from '@/card/application/command/cancel-cards-by-account-command-handler'
import { IssueCardCommandHandler } from '@/card/application/command/issue-card-command-handler'
import { SuspendCardsByAccountCommandHandler } from '@/card/application/command/suspend-cards-by-account-command-handler'
import { GetCardQueryHandler } from '@/card/application/query/get-card-query-handler'
import { AccountAdapter } from '@/card/application/adapter/account-adapter'
import { CardQuery } from '@/card/application/query/card-query'
import { CardRepository } from '@/card/domain/card-repository'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'
import { AccountAdapterImpl } from '@/card/infrastructure/account-adapter-impl'
import { CardQueryImpl } from '@/card/infrastructure/card-query-impl'
import { CardRepositoryImpl } from '@/card/infrastructure/card-repository-impl'
import { CardController } from '@/card/interface/card-controller'
import { CardIntegrationEventController } from '@/card/interface/integration-event/card-integration-event-controller'

@Module({
  imports: [CqrsModule, TypeOrmModule.forFeature([CardEntity]), AccountModule, AuthModule],
  controllers: [CardController],
  providers: [
    // Command Handlers
    IssueCardCommandHandler,
    SuspendCardsByAccountCommandHandler,
    CancelCardsByAccountCommandHandler,
    // Query Handlers
    GetCardQueryHandler,
    // Integration Event 수신부 (외부 BC → Card)
    CardIntegrationEventController,
    // Repositories
    { provide: CardRepository, useClass: CardRepositoryImpl },
    // Query 구현체
    { provide: CardQuery, useClass: CardQueryImpl },
    // 크로스 도메인 Adapter (Card → Account 동기 조회)
    { provide: AccountAdapter, useClass: AccountAdapterImpl }
  ],
  // 다른 BC(Payment)가 Adapter(ACL)를 통해 카드를 동기 조회할 수 있도록 읽기 서비스만
  // 공개한다(AccountModule의 기존 exports: [AccountQuery]와 동일한 패턴).
  // Repository·도메인 객체는 공개하지 않는다.
  exports: [CardQuery]
})
export class CardModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly cardIntegrationEventController: CardIntegrationEventController
  ) {}

  // Account BC가 발행하는 Integration Event를 자기 수신부에 연결한다.
  // eventName 리터럴(account.suspended.v1 등)이 Outbox row의 eventType이며,
  // OutboxRelay가 드레인 시 정적 맵에 없는 이 eventType을 레지스트리로 위임한다.
  onModuleInit(): void {
    this.registry.register('account.suspended.v1', (payload) =>
      this.cardIntegrationEventController.onAccountSuspended(payload as never))
    this.registry.register('account.closed.v1', (payload) =>
      this.cardIntegrationEventController.onAccountClosed(payload as never))
  }
}
