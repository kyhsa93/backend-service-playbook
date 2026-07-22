import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { ApplyDailyInterestCommandHandler } from '@/account/application/command/apply-daily-interest-command-handler'
import { CloseAccountCommandHandler } from '@/account/application/command/close-account-command-handler'
import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { DepositByPaymentCommandHandler } from '@/account/application/command/deposit-by-payment-command-handler'
import { DepositCommandHandler } from '@/account/application/command/deposit-command-handler'
import { ReactivateAccountCommandHandler } from '@/account/application/command/reactivate-account-command-handler'
import { SuspendAccountCommandHandler } from '@/account/application/command/suspend-account-command-handler'
import { TransferCommandHandler } from '@/account/application/command/transfer-command-handler'
import { WithdrawByPaymentCommandHandler } from '@/account/application/command/withdraw-by-payment-command-handler'
import { WithdrawCommandHandler } from '@/account/application/command/withdraw-command-handler'
import { AccountIntegrationEventController } from '@/account/interface/integration-event/account-integration-event-controller'
import { AccountClosedHandler } from '@/account/application/event/account-closed-handler'
import { AccountCreatedHandler } from '@/account/application/event/account-created-handler'
import { AccountReactivatedHandler } from '@/account/application/event/account-reactivated-handler'
import { AccountSuspendedHandler } from '@/account/application/event/account-suspended-handler'
import { InterestPaidHandler } from '@/account/application/event/interest-paid-handler'
import { MoneyDepositedHandler } from '@/account/application/event/money-deposited-handler'
import { MoneyWithdrawnHandler } from '@/account/application/event/money-withdrawn-handler'
import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountQueryHandler } from '@/account/application/query/get-account-query-handler'
import { GetTransactionsQueryHandler } from '@/account/application/query/get-transactions-query-handler'
import { NotificationService } from '@/account/application/service/notification-service'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountInterestScheduler } from '@/account/infrastructure/account-interest-scheduler'
import { AccountQueryImpl } from '@/account/infrastructure/account-query-impl'
import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'
import { NotificationServiceImpl } from '@/account/infrastructure/notification/notification-service-impl'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { SesClientProvider } from '@/account/infrastructure/notification/ses-client-provider'
import { AccountController } from '@/account/interface/account-controller'
import { AccountTaskController } from '@/account/interface/account-task-controller'
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
    TransferCommandHandler,
    // Command Handlers reacting to Payment BC's Integration Events (payment.completed.v1 /
    // payment.cancelled.v1 / refund.approved.v1)
    WithdrawByPaymentCommandHandler,
    DepositByPaymentCommandHandler,
    // The Command Handler the account.apply-daily-interest Task delegates to
    ApplyDailyInterestCommandHandler,
    // Query Handlers
    GetAccountQueryHandler,
    GetTransactionsQueryHandler,
    // The Integration Event receiving end (external BC → Account)
    AccountIntegrationEventController,
    // The Task input adapter — @TaskConsumer methods
    AccountTaskController,
    // Only Cron → TaskQueue.enqueue (Infrastructure layer)
    AccountInterestScheduler,
    // Event Handlers
    AccountCreatedHandler,
    MoneyDepositedHandler,
    MoneyWithdrawnHandler,
    InterestPaidHandler,
    AccountSuspendedHandler,
    AccountReactivatedHandler,
    AccountClosedHandler,
    // Repositories
    { provide: AccountRepository, useClass: AccountRepositoryImpl },
    // The Query implementation
    { provide: AccountQuery, useClass: AccountQueryImpl },
    // A Technical Service — SES email sending (Account-only; revisit whether to share it if another domain needs it)
    { provide: NotificationService, useClass: NotificationServiceImpl },
    SesClientProvider
  ],
  // Only the read service is exposed, so another BC (Card) can synchronously look up an
  // account via an Adapter (ACL). The Repository and domain objects are never exposed.
  exports: [AccountQuery]
})
export class AccountModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly accountIntegrationEventController: AccountIntegrationEventController,
    private readonly accountCreatedHandler: AccountCreatedHandler,
    private readonly moneyDepositedHandler: MoneyDepositedHandler,
    private readonly moneyWithdrawnHandler: MoneyWithdrawnHandler,
    private readonly interestPaidHandler: InterestPaidHandler,
    private readonly accountSuspendedHandler: AccountSuspendedHandler,
    private readonly accountReactivatedHandler: AccountReactivatedHandler,
    private readonly accountClosedHandler: AccountClosedHandler
  ) {}

  // Registers both this domain's own Domain Event handlers (called when the OutboxConsumer
  // receives them from SQS) and the receiving end for Payment BC's Integration Events into
  // the same shared EventHandlerRegistry — no per-domain dedicated Relay file is kept.
  onModuleInit(): void {
    this.registry.register('AccountCreated', (payload) => this.accountCreatedHandler.handle(payload as never))
    this.registry.register('MoneyDeposited', (payload) => this.moneyDepositedHandler.handle(payload as never))
    this.registry.register('MoneyWithdrawn', (payload) => this.moneyWithdrawnHandler.handle(payload as never))
    this.registry.register('InterestPaid', (payload) => this.interestPaidHandler.handle(payload as never))
    this.registry.register('AccountSuspended', (payload) => this.accountSuspendedHandler.handle(payload as never))
    this.registry.register('AccountReactivated', (payload) => this.accountReactivatedHandler.handle(payload as never))
    this.registry.register('AccountClosed', (payload) => this.accountClosedHandler.handle(payload as never))

    // Wires Payment BC's published Integration Events to this domain's own receiving end.
    // The same pattern as CardModule subscribing to account.suspended.v1/account.closed.v1 —
    // this only adds a registration and doesn't touch any Card-related registration or code.
    this.registry.register('payment.completed.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCompleted(payload as never))
    this.registry.register('payment.cancelled.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCancelled(payload as never))
    this.registry.register('refund.approved.v1', (payload) =>
      this.accountIntegrationEventController.onRefundApproved(payload as never))
  }
}
