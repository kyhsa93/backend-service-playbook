import { Injectable, Logger } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { AccountClosedHandler } from '@/account/application/event/account-closed-handler'
import { AccountCreatedHandler } from '@/account/application/event/account-created-handler'
import { AccountReactivatedHandler } from '@/account/application/event/account-reactivated-handler'
import { AccountSuspendedHandler } from '@/account/application/event/account-suspended-handler'
import { MoneyDepositedHandler } from '@/account/application/event/money-deposited-handler'
import { MoneyWithdrawnHandler } from '@/account/application/event/money-withdrawn-handler'

@Injectable()
export class OutboxRelay {
  private readonly logger = new Logger(OutboxRelay.name)
  private readonly handlers: Record<string, (payload: object) => Promise<void>>

  constructor(
    private readonly transactionManager: TransactionManager,
    private readonly registry: EventHandlerRegistry,
    accountCreatedHandler: AccountCreatedHandler,
    moneyDepositedHandler: MoneyDepositedHandler,
    moneyWithdrawnHandler: MoneyWithdrawnHandler,
    accountSuspendedHandler: AccountSuspendedHandler,
    accountReactivatedHandler: AccountReactivatedHandler,
    accountClosedHandler: AccountClosedHandler
  ) {
    this.handlers = {
      AccountCreated: (payload) => accountCreatedHandler.handle(payload as never),
      MoneyDeposited: (payload) => moneyDepositedHandler.handle(payload as never),
      MoneyWithdrawn: (payload) => moneyWithdrawnHandler.handle(payload as never),
      AccountSuspended: (payload) => accountSuspendedHandler.handle(payload as never),
      AccountReactivated: (payload) => accountReactivatedHandler.handle(payload as never),
      AccountClosed: (payload) => accountClosedHandler.handle(payload as never)
    }
  }

  // 미처리 outbox 행을 드레인한다. 핸들러 실행 도중 새 outbox 행이 적재될 수 있으므로
  // (예: AccountSuspended Domain Event 핸들러가 account.suspended.v1 Integration Event를
  // 적재) 더 이상 진전이 없을 때까지 여러 패스로 반복 드레인한다. 이렇게 하면
  // Domain Event → Integration Event → 외부 BC 수신이 한 커맨드 처리 안에서 완결된다.
  public async processPending(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const MAX_PASSES = 10
    // 이번 호출에서 이미 실패한 행은 다음 패스에서 재시도하지 않는다 — 실패는 다음
    // processPending() 호출의 몫이다. 이렇게 하면 한 번 실패한 행(예: 알림 미가용)을
    // 매 패스마다 다시 시도하는 낭비 없이, 드레인 도중 새로 적재된 행만 이어서 처리한다.
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
          // 정적 맵에 없는 eventType(다른 BC가 발행한 Integration Event 등)은 레지스트리로 위임.
          // 등록된 핸들러가 없으면 no-op — 소비자 없는 이벤트는 드레인만 하고 넘어간다.
          else await this.registry.handle(row.eventType, JSON.parse(row.payload))
          await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
          progressed++
        } catch (error) {
          // 후속 처리 실패가 커맨드 자체를 실패시키지 않도록 격리한다.
          // processed를 true로 남기지 않아 다음 processPending() 호출 때 재시도된다.
          failedInThisRun.add(row.eventId)
          this.logger.error({ message: '이벤트 처리 실패', event_type: row.eventType, event_id: row.eventId, error })
        }
      }
      // 이번 패스에서 아무 행도 처리하지 못했다면 더 진전될 여지가 없으므로 종료한다.
      if (progressed === 0) return
    }
  }
}
