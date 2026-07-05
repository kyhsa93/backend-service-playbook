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
      eventType: event.constructor.name,
      payload: JSON.stringify(event),
      processed: false
    })))
  }
}
