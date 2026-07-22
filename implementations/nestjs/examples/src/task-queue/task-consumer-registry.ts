import { Injectable } from '@nestjs/common'
import { ModuleRef } from '@nestjs/core'

import { getTaskHandler } from './task-consumer.decorator'

// Finds and calls the Task Controller method registered under a taskType. A Task Controller
// must be registered in its own domain module's providers for ModuleRef to be able to resolve
// the instance (see docs/architecture/scheduling.md, the Module Registration section).
//
// Both Tasks (account.apply-daily-interest, payment.send-card-statements) are designed with
// state-based inherent idempotency (Level 1), so this registry keeps no separate ledger — if a
// Task with significant side effects is added, a Level 2 (ledger) can be layered on here the
// same way EventHandlerRegistry does (see scheduling.md, the Idempotency section).
@Injectable()
export class TaskConsumerRegistry {
  constructor(private readonly moduleRef: ModuleRef) {}

  public async dispatch(taskType: string, payload: object): Promise<void> {
    const entry = getTaskHandler(taskType)
    if (!entry) {
      throw new Error(`No @TaskConsumer registered for taskType: ${taskType}`)
    }

    const handler = this.moduleRef.get(entry.handlerClass, { strict: false })
    await (handler as Record<string, (p: object) => Promise<void>>)[entry.method](payload)
  }
}
