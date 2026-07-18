import { Injectable, Logger } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { PaymentCancelledHandler } from '@/payment/application/event/payment-cancelled-handler'
import { PaymentCompletedHandler } from '@/payment/application/event/payment-completed-handler'
import { RefundApprovedHandler } from '@/payment/application/event/refund-approved-handler'

// Account BC의 OutboxRelay와 동일한 메커니즘(정적 맵 우선, 미상 eventType은
// EventHandlerRegistry로 위임, 진전이 없을 때까지 여러 패스로 드레인)을 그대로
// 따른다 — Payment도 자신의 Domain Event를 Outbox에 적재하는 두 번째 BC이므로
// Account의 OutboxRelay를 참조 템플릿으로 재사용했다.
@Injectable()
export class OutboxRelay {
  private readonly logger = new Logger(OutboxRelay.name)
  private readonly handlers: Record<string, (payload: object) => Promise<void>>

  constructor(
    private readonly transactionManager: TransactionManager,
    private readonly registry: EventHandlerRegistry,
    paymentCompletedHandler: PaymentCompletedHandler,
    paymentCancelledHandler: PaymentCancelledHandler,
    refundApprovedHandler: RefundApprovedHandler
  ) {
    this.handlers = {
      PaymentCompleted: (payload) => paymentCompletedHandler.handle(payload as never),
      PaymentCancelled: (payload) => paymentCancelledHandler.handle(payload as never),
      RefundApproved: (payload) => refundApprovedHandler.handle(payload as never)
    }
  }

  public async processPending(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const MAX_PASSES = 10
    const failedInThisRun = new Set<string>()

    for (let pass = 0; pass < MAX_PASSES; pass++) {
      const rows = (await manager.findBy(OutboxEntity, { processed: false }))
        .filter((row) => !failedInThisRun.has(row.eventId))
      if (rows.length === 0) return

      let progressed = 0
      for (const row of rows) {
        try {
          const handler = this.handlers[row.eventType]
          if (handler) await handler(JSON.parse(row.payload))
          else await this.registry.handle(row.eventType, JSON.parse(row.payload))
          await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
          progressed++
        } catch (error) {
          failedInThisRun.add(row.eventId)
          this.logger.error({ message: '이벤트 처리 실패', event_type: row.eventType, event_id: row.eventId, error })
        }
      }
      if (progressed === 0) return
    }
  }
}
