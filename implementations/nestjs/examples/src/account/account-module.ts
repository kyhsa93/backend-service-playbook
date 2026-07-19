import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { CloseAccountCommandHandler } from '@/account/application/command/close-account-command-handler'
import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { DepositByPaymentCommandHandler } from '@/account/application/command/deposit-by-payment-command-handler'
import { DepositCommandHandler } from '@/account/application/command/deposit-command-handler'
import { ReactivateAccountCommandHandler } from '@/account/application/command/reactivate-account-command-handler'
import { SuspendAccountCommandHandler } from '@/account/application/command/suspend-account-command-handler'
import { WithdrawByPaymentCommandHandler } from '@/account/application/command/withdraw-by-payment-command-handler'
import { WithdrawCommandHandler } from '@/account/application/command/withdraw-command-handler'
import { AccountIntegrationEventController } from '@/account/interface/integration-event/account-integration-event-controller'
import { AccountClosedHandler } from '@/account/application/event/account-closed-handler'
import { AccountCreatedHandler } from '@/account/application/event/account-created-handler'
import { AccountReactivatedHandler } from '@/account/application/event/account-reactivated-handler'
import { AccountSuspendedHandler } from '@/account/application/event/account-suspended-handler'
import { MoneyDepositedHandler } from '@/account/application/event/money-deposited-handler'
import { MoneyWithdrawnHandler } from '@/account/application/event/money-withdrawn-handler'
import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountQueryHandler } from '@/account/application/query/get-account-query-handler'
import { GetTransactionsQueryHandler } from '@/account/application/query/get-transactions-query-handler'
import { NotificationService } from '@/account/application/service/notification-service'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountQueryImpl } from '@/account/infrastructure/account-query-impl'
import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'
import { NotificationServiceImpl } from '@/account/infrastructure/notification/notification-service-impl'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { SesClientProvider } from '@/account/infrastructure/notification/ses-client-provider'
import { AccountController } from '@/account/interface/account-controller'
import { AuthModule } from '@/auth/auth-module'

@Module({
  imports: [
    CqrsModule,
    TypeOrmModule.forFeature([AccountEntity, TransactionEntity, SentEmailEntity]),
    AuthModule
  ],
  controllers: [AccountController],
  providers: [
    // Command Handlers
    CreateAccountCommandHandler,
    DepositCommandHandler,
    WithdrawCommandHandler,
    SuspendAccountCommandHandler,
    ReactivateAccountCommandHandler,
    CloseAccountCommandHandler,
    // Payment BC의 Integration Event(payment.completed.v1 / payment.cancelled.v1 /
    // refund.approved.v1)에 대한 반응 Command Handler
    WithdrawByPaymentCommandHandler,
    DepositByPaymentCommandHandler,
    // Query Handlers
    GetAccountQueryHandler,
    GetTransactionsQueryHandler,
    // Integration Event 수신부 (외부 BC → Account)
    AccountIntegrationEventController,
    // Event Handlers
    AccountCreatedHandler,
    MoneyDepositedHandler,
    MoneyWithdrawnHandler,
    AccountSuspendedHandler,
    AccountReactivatedHandler,
    AccountClosedHandler,
    // Repositories
    { provide: AccountRepository, useClass: AccountRepositoryImpl },
    // Query 구현체
    { provide: AccountQuery, useClass: AccountQueryImpl },
    // Technical Service — SES 이메일 발송 (Account 전용, 다른 도메인이 필요로 하면 그때 공유 여부 재검토)
    { provide: NotificationService, useClass: NotificationServiceImpl },
    SesClientProvider
  ],
  // 다른 BC(Card)가 Adapter(ACL)를 통해 계좌를 동기 조회할 수 있도록 읽기 서비스만 공개한다.
  // Repository·도메인 객체는 공개하지 않는다.
  exports: [AccountQuery]
})
export class AccountModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly accountIntegrationEventController: AccountIntegrationEventController,
    private readonly accountCreatedHandler: AccountCreatedHandler,
    private readonly moneyDepositedHandler: MoneyDepositedHandler,
    private readonly moneyWithdrawnHandler: MoneyWithdrawnHandler,
    private readonly accountSuspendedHandler: AccountSuspendedHandler,
    private readonly accountReactivatedHandler: AccountReactivatedHandler,
    private readonly accountClosedHandler: AccountClosedHandler
  ) {}

  // 자기 도메인이 발행하는 Domain Event(OutboxConsumer가 SQS에서 수신했을 때 호출할
  // 핸들러)와, Payment BC가 발행하는 Integration Event 수신부를 모두 같은
  // EventHandlerRegistry에 등록한다. 예전에는 이 라우팅을
  // account/application/event/outbox-relay.ts의 생성자 주입 고정 맵이 담당했지만,
  // Account/Payment가 각자 별도 Relay를 두던 구조를 하나의 공유 outbox 모듈로
  // 통합하면서 모든 라우팅이 이 레지스트리 하나로 모인다.
  onModuleInit(): void {
    this.registry.register('AccountCreated', (payload) => this.accountCreatedHandler.handle(payload as never))
    this.registry.register('MoneyDeposited', (payload) => this.moneyDepositedHandler.handle(payload as never))
    this.registry.register('MoneyWithdrawn', (payload) => this.moneyWithdrawnHandler.handle(payload as never))
    this.registry.register('AccountSuspended', (payload) => this.accountSuspendedHandler.handle(payload as never))
    this.registry.register('AccountReactivated', (payload) => this.accountReactivatedHandler.handle(payload as never))
    this.registry.register('AccountClosed', (payload) => this.accountClosedHandler.handle(payload as never))

    // Payment BC가 발행하는 Integration Event를 자기 수신부에 연결한다. CardModule이
    // account.suspended.v1/account.closed.v1을 구독하는 것과 동일한 패턴 —
    // 등록만 추가되며 Card 관련 등록·코드는 건드리지 않는다.
    this.registry.register('payment.completed.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCompleted(payload as never))
    this.registry.register('payment.cancelled.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCancelled(payload as never))
    this.registry.register('refund.approved.v1', (payload) =>
      this.accountIntegrationEventController.onRefundApproved(payload as never))
  }
}
