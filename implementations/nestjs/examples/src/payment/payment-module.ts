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
import { PaymentCommandService } from '@/payment/application/service/payment-command-service'
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
import { RefundQueryImpl } from '@/payment/infrastructure/refund-query-impl'
import { RefundRepositoryImpl } from '@/payment/infrastructure/refund-repository-impl'
import { PaymentController } from '@/payment/interface/payment-controller'
import { PaymentTaskController } from '@/payment/interface/payment-task-controller'

// Payment BC는 Card BC의 application/adapter/·infrastructure/*-adapter-impl.ts·
// interface/integration-event/ 구조를 템플릿으로 재사용한 3번째 도메인이다.
// Card와 달리 Payment는 외부 BC의 Integration Event를 구독하지 않는다(발행만 한다) —
// payment.completed.v1 / payment.cancelled.v1 / refund.approved.v1을 구독해 반응하는
// 쪽은 Account BC다(account/interface/integration-event/ 참고).
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
    // payment.send-card-statements Task가 위임하는 Command Handler
    SendCardStatementsCommandHandler,
    // Query Handlers
    GetPaymentQueryHandler,
    GetPaymentsQueryHandler,
    GetRefundsQueryHandler,
    // Domain Event Handlers (Payment/Refund → Integration Event 변환)
    PaymentCompletedHandler,
    PaymentCancelledHandler,
    RefundApprovedHandler,
    // Task 입력 어댑터 — @TaskConsumer 메서드
    PaymentTaskController,
    // Cron → TaskQueue.enqueue만 수행 (Infrastructure 레이어)
    CardStatementScheduler,
    // Task Controller가 주입받는 얇은 Command Service (CommandBus 위임)
    PaymentCommandService,
    // Repositories
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl },
    { provide: RefundRepository, useClass: RefundRepositoryImpl },
    // Query 구현체
    { provide: PaymentQuery, useClass: PaymentQueryImpl },
    { provide: RefundQuery, useClass: RefundQueryImpl },
    // 크로스 도메인 Adapter (Payment → Card, Payment → Account 동기 조회)
    { provide: CardAdapter, useClass: CardAdapterImpl },
    { provide: AccountAdapter, useClass: AccountAdapterImpl },
    // Technical Service — SES 카드 사용내역 발송 (Payment 전용, Account의 NotificationService와 별개)
    { provide: CardStatementNotificationService, useClass: CardStatementNotificationServiceImpl },
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

  // Account BC의 account-module.ts와 동일한 패턴 — 자기 도메인이 발행하는 Domain
  // Event를 OutboxConsumer가 SQS에서 수신했을 때 호출할 핸들러로 EventHandlerRegistry에
  // 등록한다. 예전에는 payment/application/event/outbox-relay.ts의 고정 맵이 담당했다.
  onModuleInit(): void {
    this.registry.register('PaymentCompleted', (payload) => this.paymentCompletedHandler.handle(payload as never))
    this.registry.register('PaymentCancelled', (payload) => this.paymentCancelledHandler.handle(payload as never))
    this.registry.register('RefundApproved', (payload) => this.refundApprovedHandler.handle(payload as never))
  }
}
