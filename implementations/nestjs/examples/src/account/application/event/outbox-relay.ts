import { Injectable, Logger } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
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

  public async processPending(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.findBy(OutboxEntity, { processed: false })

    for (const row of rows) {
      const handler = this.handlers[row.eventType]
      try {
        if (handler) await handler(JSON.parse(row.payload))
        await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
      } catch (error) {
        // 알림 발송 실패가 계좌 커맨드 자체를 실패시키지 않도록 격리한다.
        // processed를 true로 남기지 않아 다음 processPending() 호출 때 재시도된다.
        this.logger.error({ message: '이벤트 처리 실패', event_type: row.eventType, event_id: row.eventId, error })
      }
    }
  }
}
