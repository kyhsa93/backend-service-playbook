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
    // The Integration Event receiving end (external BC → Card)
    CardIntegrationEventController,
    // Repositories
    { provide: CardRepository, useClass: CardRepositoryImpl },
    // The Query implementation
    { provide: CardQuery, useClass: CardQueryImpl },
    // A cross-domain Adapter (Card → Account synchronous lookup)
    { provide: AccountAdapter, useClass: AccountAdapterImpl }
  ],
  // Only the read service is exposed so another BC (Payment) can synchronously look up a card
  // via an Adapter (ACL) (the same pattern as AccountModule's existing exports: [AccountQuery]).
  // The Repository and domain objects are never exposed.
  exports: [CardQuery]
})
export class CardModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly cardIntegrationEventController: CardIntegrationEventController
  ) {}

  // Wires the Integration Events published by Account BC to this domain's own receiving end.
  // The eventName literal (account.suspended.v1, etc.) is the Outbox row's eventType, and when
  // OutboxConsumer receives this eventType from SQS, it looks up and calls the handler in this registry.
  onModuleInit(): void {
    this.registry.register('account.suspended.v1', (payload) =>
      this.cardIntegrationEventController.onAccountSuspended(payload as never))
    this.registry.register('account.closed.v1', (payload) =>
      this.cardIntegrationEventController.onAccountClosed(payload as never))
  }
}
