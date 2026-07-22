import { Injectable } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

// A Task input adapter (Interface layer) — just as the HTTP Controller
// (account-controller.ts) receives a REST request and delegates to CommandBus.execute(), this
// receives a Task queue message and likewise delegates to the CommandBus. Since Account is a
// domain that uses the @nestjs/cqrs approach (CommandHandler), it doesn't keep a separate
// Service-style CommandService (see cqrs-pattern.md's selection criteria). It delegates with
// no logic, and throws exceptions as-is so TaskQueueConsumer can decide retry/DLQ (see
// docs/architecture/scheduling.md, the TaskController section).
@Injectable()
export class AccountTaskController {
  constructor(private readonly commandBus: CommandBus) {}

  @TaskConsumer('account.apply-daily-interest')
  public async applyDailyInterest(payload: { today: string }): Promise<void> {
    // The payload is a plain object from JSON.parse-ing the SQS message — it's an ISO string,
    // not a Date instance, so it's reconstructed at Command-creation time (the same pattern as
    // the HTTP Controller wrapping the request body into a Command via new Command({ ...body })).
    await this.commandBus.execute<ApplyDailyInterestCommand, number>(
      new ApplyDailyInterestCommand({ today: new Date(payload.today) })
    )
  }
}
