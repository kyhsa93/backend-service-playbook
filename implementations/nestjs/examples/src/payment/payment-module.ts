import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { CardModule } from '@/card/card-module'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { CancelPaymentCommandHandler } from '@/payment/application/command/cancel-payment-command-handler'
import { CreatePaymentCommandHandler } from '@/payment/application/command/create-payment-command-handler'
import { RequestRefundCommandHandler } from '@/payment/application/command/request-refund-command-handler'
import { PaymentCancelledHandler } from '@/payment/application/event/payment-cancelled-handler'
import { PaymentCompletedHandler } from '@/payment/application/event/payment-completed-handler'
import { RefundApprovedHandler } from '@/payment/application/event/refund-approved-handler'
import { OutboxRelay } from '@/payment/application/event/outbox-relay'
import { GetPaymentQueryHandler } from '@/payment/application/query/get-payment-query-handler'
import { GetPaymentsQueryHandler } from '@/payment/application/query/get-payments-query-handler'
import { GetRefundsQueryHandler } from '@/payment/application/query/get-refunds-query-handler'
import { PaymentQuery } from '@/payment/application/query/payment-query'
import { RefundQuery } from '@/payment/application/query/refund-query'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { RefundRepository } from '@/payment/domain/refund-repository'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { AccountAdapterImpl } from '@/payment/infrastructure/account-adapter-impl'
import { CardAdapterImpl } from '@/payment/infrastructure/card-adapter-impl'
import { PaymentQueryImpl } from '@/payment/infrastructure/payment-query-impl'
import { PaymentRepositoryImpl } from '@/payment/infrastructure/payment-repository-impl'
import { RefundQueryImpl } from '@/payment/infrastructure/refund-query-impl'
import { RefundRepositoryImpl } from '@/payment/infrastructure/refund-repository-impl'
import { PaymentController } from '@/payment/interface/payment-controller'

// Payment BC는 Card BC의 application/adapter/·infrastructure/*-adapter-impl.ts·
// interface/integration-event/ 구조를 템플릿으로 재사용한 3번째 도메인이다.
// Card와 달리 Payment는 외부 BC의 Integration Event를 구독하지 않는다(발행만 한다) —
// payment.completed.v1 / payment.cancelled.v1 / refund.approved.v1을 구독해 반응하는
// 쪽은 Account BC다(account/interface/integration-event/ 참고).
@Module({
  imports: [
    CqrsModule,
    TypeOrmModule.forFeature([PaymentEntity, RefundEntity]),
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
    // Query Handlers
    GetPaymentQueryHandler,
    GetPaymentsQueryHandler,
    GetRefundsQueryHandler,
    // Domain Event Handlers (Payment/Refund → Integration Event 변환)
    PaymentCompletedHandler,
    PaymentCancelledHandler,
    RefundApprovedHandler,
    // Outbox 릴레이 (미처리 outbox 이벤트를 위 핸들러로 전달)
    OutboxRelay,
    // Repositories
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl },
    { provide: RefundRepository, useClass: RefundRepositoryImpl },
    // Query 구현체
    { provide: PaymentQuery, useClass: PaymentQueryImpl },
    { provide: RefundQuery, useClass: RefundQueryImpl },
    // 크로스 도메인 Adapter (Payment → Card, Payment → Account 동기 조회)
    { provide: CardAdapter, useClass: CardAdapterImpl },
    { provide: AccountAdapter, useClass: AccountAdapterImpl }
  ]
})
export class PaymentModule {}
