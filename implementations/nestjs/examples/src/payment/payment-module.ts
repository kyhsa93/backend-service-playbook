import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { CardModule } from '@/card/card-module'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { CancelPaymentCommandHandler } from '@/payment/application/command/cancel-payment-command-handler'
import { CreatePaymentCommandHandler } from '@/payment/application/command/create-payment-command-handler'
import { RequestRefundCommandHandler } from '@/payment/application/command/request-refund-command-handler'
import { SendCardStatementsCommandHandler } from '@/payment/application/command/send-card-statements-command-handler'
import { PaymentCancelledHandler } from '@/payment/application/event/payment-cancelled-handler'
import { PaymentCompletedHandler } from '@/payment/application/event/payment-completed-handler'
import { RefundApprovedHandler } from '@/payment/application/event/refund-approved-handler'
import { GetPaymentQueryHandler } from '@/payment/application/query/get-payment-query-handler'
import { GetPaymentsQueryHandler } from '@/payment/application/query/get-payments-query-handler'
import { GetRefundsQueryHandler } from '@/payment/application/query/get-refunds-query-handler'
import { PaymentQuery } from '@/payment/application/query/payment-query'
import { RefundQuery } from '@/payment/application/query/refund-query'
import { CardStatementNotificationService } from '@/payment/application/service/card-statement-notification-service'
import { RefundFraudRiskScorer } from '@/payment/application/service/refund-fraud-risk-scorer'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { AccountAdapterImpl } from '@/payment/infrastructure/account-adapter-impl'
import { CardAdapterImpl } from '@/payment/infrastructure/card-adapter-impl'
import { CardStatementScheduler } from '@/payment/infrastructure/card-statement-scheduler'
import { CardStatementNotificationServiceImpl } from '@/payment/infrastructure/notification/card-statement-notification-service-impl'
import { PaymentSesClientProvider } from '@/payment/infrastructure/notification/ses-client-provider'
import { SentCardStatementEntity } from '@/payment/infrastructure/notification/sent-card-statement.entity'
import { PaymentQueryImpl } from '@/payment/infrastructure/payment-query-impl'
import { PaymentRepositoryImpl } from '@/payment/infrastructure/payment-repository-impl'
import { RefundFraudRiskScorerHttpImpl } from '@/payment/infrastructure/refund-fraud-risk-scorer-http-impl'
import { RefundFraudRiskScorerNativeImpl } from '@/payment/infrastructure/refund-fraud-risk-scorer-native-impl'
import { RefundQueryImpl } from '@/payment/infrastructure/refund-query-impl'
import { RefundReasonClassifierImpl } from '@/payment/infrastructure/refund-reason-classifier-impl'
import { RefundRepositoryImpl } from '@/payment/infrastructure/refund-repository-impl'
import { PaymentController } from '@/payment/interface/payment-controller'
import { PaymentTaskController } from '@/payment/interface/payment-task-controller'
import { getFraudScorerMode } from '@/config/fraud-risk.config'

// Payment BC is the 3rd domain, reusing Card BC's application/adapter/·
// infrastructure/*-adapter-impl.ts·interface/integration-event/ structure as a template.
// Unlike Card, Payment never subscribes to an external BC's Integration Event (it only
// publishes) — the side subscribing to and reacting to payment.completed.v1 /
// payment.cancelled.v1 / refund.approved.v1 is Account BC (see account/interface/integration-event/).
@Module({
  imports: [
    CqrsModule,
    TypeOrmModule.forFeature([PaymentEntity, RefundEntity, SentCardStatementEntity]),
    CardModule,
    AccountModule,
    AuthModule
  ],
  controllers: [PaymentController],
  providers: [
    // Command Handlers
    CreatePaymentCommandHandler,
    CancelPaymentCommandHandler,
    RequestRefundCommandHandler,
    // The Command Handler the payment.send-card-statements Task delegates to
    SendCardStatementsCommandHandler,
    // Query Handlers
    GetPaymentQueryHandler,
    GetPaymentsQueryHandler,
    GetRefundsQueryHandler,
    // Domain Event Handlers (converting Payment/Refund → Integration Event)
    PaymentCompletedHandler,
    PaymentCancelledHandler,
    RefundApprovedHandler,
    // The Task input adapter — @TaskConsumer methods
    PaymentTaskController,
    // Only Cron → TaskQueue.enqueue (Infrastructure layer)
    CardStatementScheduler,
    // Repositories
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl },
    { provide: RefundRepository, useClass: RefundRepositoryImpl },
    // The Query implementation
    { provide: PaymentQuery, useClass: PaymentQueryImpl },
    { provide: RefundQuery, useClass: RefundQueryImpl },
    // Cross-domain Adapters (Payment → Card, Payment → Account synchronous lookup)
    { provide: CardAdapter, useClass: CardAdapterImpl },
    { provide: AccountAdapter, useClass: AccountAdapterImpl },
    // A Technical Service — SES card-statement sending (Payment-only, separate from Account's NotificationService)
    { provide: CardStatementNotificationService, useClass: CardStatementNotificationServiceImpl },
    // A Technical Service — LLM-based refund reason classification (see refund-eligibility-service.ts)
    { provide: RefundReasonClassifier, useClass: RefundReasonClassifierImpl },
    // A Technical Service — ML-based refund fraud-risk scoring (see refund-eligibility-service.ts).
    // Both concrete implementations are registered so the factory below can pick either one at
    // runtime; only the one FRAUD_SCORER_MODE selects is ever actually called.
    RefundFraudRiskScorerNativeImpl,
    RefundFraudRiskScorerHttpImpl,
    {
      provide: RefundFraudRiskScorer,
      useFactory: (native: RefundFraudRiskScorerNativeImpl, http: RefundFraudRiskScorerHttpImpl) =>
        getFraudScorerMode() === 'http' ? http : native,
      inject: [RefundFraudRiskScorerNativeImpl, RefundFraudRiskScorerHttpImpl]
    },
    PaymentSesClientProvider
  ]
})
export class PaymentModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly paymentCompletedHandler: PaymentCompletedHandler,
    private readonly paymentCancelledHandler: PaymentCancelledHandler,
    private readonly refundApprovedHandler: RefundApprovedHandler
  ) {}

  // The same pattern as Account BC's account-module.ts — registers this domain's own
  // published Domain Events as handlers to be called when OutboxConsumer receives them from
  // SQS, into the shared EventHandlerRegistry — no per-domain dedicated Relay file is kept.
  onModuleInit(): void {
    this.registry.register('PaymentCompleted', (payload) => this.paymentCompletedHandler.handle(payload as never))
    this.registry.register('PaymentCancelled', (payload) => this.paymentCancelledHandler.handle(payload as never))
    this.registry.register('RefundApproved', (payload) => this.refundApprovedHandler.handle(payload as never))
  }
}
