import { Injectable } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { traceParentFromContext } from '@/outbox/trace-context'

@Injectable()
export class OutboxWriter {
  constructor(private readonly transactionManager: TransactionManager) {}

  public async saveAll(events: object[]): Promise<void> {
    const manager = this.transactionManager.getManager()
    // Captured once per call, not per event — every event in a single saveAll() batch belongs
    // to the same Repository.save() transaction, hence the same originating request/trace.
    const traceParent = traceParentFromContext() ?? null
    await manager.insert(OutboxEntity, events.map((event) => ({
      eventId: generateId(),
      // An Integration Event uses its versioned public-contract name (eventName, e.g.
      // 'account.suspended.v1') as the eventType. A Domain Event has no eventName, so the class name is used as-is.
      eventType: (event as { eventName?: string }).eventName ?? event.constructor.name,
      payload: JSON.stringify(event),
      processed: false,
      traceParent
    })))
  }
}
