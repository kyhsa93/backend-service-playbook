import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { AnalyzeMonthlySpendingCommandHandler } from '@/account/application/command/analyze-monthly-spending-command-handler'
import { ApplyDailyInterestCommandHandler } from '@/account/application/command/apply-daily-interest-command-handler'
import { CloseAccountCommandHandler } from '@/account/application/command/close-account-command-handler'
import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { DepositByPaymentCommandHandler } from '@/account/application/command/deposit-by-payment-command-handler'
import { DepositCommandHandler } from '@/account/application/command/deposit-command-handler'
import { ForecastSpendingCommandHandler } from '@/account/application/command/forecast-spending-command-handler'
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
import { AskTransactionHistoryQueryHandler } from '@/account/application/query/ask-transaction-history-query-handler'
import { GetAccountQueryHandler } from '@/account/application/query/get-account-query-handler'
import { GetSpendingAnalysisQueryHandler } from '@/account/application/query/get-spending-analysis-query-handler'
import { GetSpendingForecastQueryHandler } from '@/account/application/query/get-spending-forecast-query-handler'
import { GetTransactionsQueryHandler } from '@/account/application/query/get-transactions-query-handler'
import { SpendingAnalysisQuery } from '@/account/application/query/spending-analysis-query'
import { SpendingForecastQuery } from '@/account/application/query/spending-forecast-query'
import { NlTransactionAnswerComposer } from '@/account/application/service/nl-transaction-answer-composer'
import { NlTransactionQueryTranslator } from '@/account/application/service/nl-transaction-query-translator'
import { NotificationService } from '@/account/application/service/notification-service'
import { SpendingForecastModel } from '@/account/application/service/spending-forecast-model'
import { AccountRepository } from '@/account/domain/account-repository'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { SpendingForecastRepository } from '@/account/domain/spending-forecast-repository'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { SpendingAnalysisEntity } from '@/account/infrastructure/entity/spending-analysis.entity'
import { SpendingForecastEntity } from '@/account/infrastructure/entity/spending-forecast.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountInterestScheduler } from '@/account/infrastructure/account-interest-scheduler'
import { AccountQueryImpl } from '@/account/infrastructure/account-query-impl'
import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'
import { NlTransactionAnswerComposerImpl } from '@/account/infrastructure/nl-transaction-answer-composer-impl'
import { NlTransactionQueryTranslatorImpl } from '@/account/infrastructure/nl-transaction-query-translator-impl'
import { NotificationServiceImpl } from '@/account/infrastructure/notification/notification-service-impl'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { SesClientProvider } from '@/account/infrastructure/notification/ses-client-provider'
import { SpendingAnalysisQueryImpl } from '@/account/infrastructure/spending-analysis-query-impl'
import { SpendingAnalysisRepositoryImpl } from '@/account/infrastructure/spending-analysis-repository-impl'
import { SpendingAnalysisScheduler } from '@/account/infrastructure/spending-analysis-scheduler'
import { SpendingForecastModelImpl } from '@/account/infrastructure/spending-forecast-model-impl'
import { SpendingForecastQueryImpl } from '@/account/infrastructure/spending-forecast-query-impl'
import { SpendingForecastRepositoryImpl } from '@/account/infrastructure/spending-forecast-repository-impl'
import { SpendingForecastScheduler } from '@/account/infrastructure/spending-forecast-scheduler'
import { AccountController } from '@/account/interface/account-controller'
import { AccountTaskController } from '@/account/interface/account-task-controller'
import { AuthModule } from '@/auth/auth-module'

@Module({
  imports: [
    CqrsModule,
    TypeOrmModule.forFeature([AccountEntity, TransactionEntity, SentEmailEntity, SpendingAnalysisEntity, SpendingForecastEntity]),
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
    // The Command Handler the account.analyze-monthly-spending Task delegates to
    AnalyzeMonthlySpendingCommandHandler,
    // The Command Handler the account.forecast-spending Task delegates to
    ForecastSpendingCommandHandler,
    // Query Handlers
    GetAccountQueryHandler,
    GetTransactionsQueryHandler,
    AskTransactionHistoryQueryHandler,
    GetSpendingAnalysisQueryHandler,
    GetSpendingForecastQueryHandler,
    // The Integration Event receiving end (external BC → Account)
    AccountIntegrationEventController,
    // The Task input adapter — @TaskConsumer methods
    AccountTaskController,
    // Only Cron → TaskQueue.enqueue (Infrastructure layer)
    AccountInterestScheduler,
    SpendingAnalysisScheduler,
    SpendingForecastScheduler,
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
    { provide: SpendingAnalysisRepository, useClass: SpendingAnalysisRepositoryImpl },
    { provide: SpendingForecastRepository, useClass: SpendingForecastRepositoryImpl },
    // The Query implementation
    { provide: AccountQuery, useClass: AccountQueryImpl },
    { provide: SpendingAnalysisQuery, useClass: SpendingAnalysisQueryImpl },
    { provide: SpendingForecastQuery, useClass: SpendingForecastQueryImpl },
    // A Technical Service — SES email sending (Account-only; revisit whether to share it if another domain needs it)
    { provide: NotificationService, useClass: NotificationServiceImpl },
    SesClientProvider,
    // Technical Services — the two LLM calls behind AskTransactionHistoryQueryHandler's
    // structured-data RAG pipeline (see root docs/architecture/domain-service.md)
    { provide: NlTransactionQueryTranslator, useClass: NlTransactionQueryTranslatorImpl },
    { provide: NlTransactionAnswerComposer, useClass: NlTransactionAnswerComposerImpl },
    // A Technical Service — the in-process regression model behind account.forecast-spending
    // (see root docs/architecture/domain-service.md)
    { provide: SpendingForecastModel, useClass: SpendingForecastModelImpl }
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
