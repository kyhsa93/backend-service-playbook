import { Injectable } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'
import { OutboxEntity } from '@/outbox/outbox.entity'

@Injectable()
export class OutboxWriter {
  constructor(private readonly transactionManager: TransactionManager) {}

  public async saveAll(events: object[]): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.insert(OutboxEntity, events.map((event) => ({
      eventId: generateId(),
      // Integration Event는 버전이 명시된 공개 계약명(eventName, 예: 'account.suspended.v1')을
      // eventType으로 쓴다. Domain Event는 eventName이 없으므로 클래스명을 그대로 쓴다.
      eventType: (event as { eventName?: string }).eventName ?? event.constructor.name,
      payload: JSON.stringify(event),
      processed: false
    })))
  }
}
