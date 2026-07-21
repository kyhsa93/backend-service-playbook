import { Injectable } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { SendCardStatementsCommand } from '@/payment/application/command/send-card-statements-command'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

// account/interface/account-task-controller.ts와 같은 모양의 Task 입력 어댑터다.
@Injectable()
export class PaymentTaskController {
  constructor(private readonly commandBus: CommandBus) {}

  @TaskConsumer('payment.send-card-statements')
  public async sendCardStatements(payload: { statementMonth: string; monthStart: string; monthEnd: string }): Promise<void> {
    await this.commandBus.execute<SendCardStatementsCommand, number>(new SendCardStatementsCommand({
      statementMonth: payload.statementMonth,
      monthStart: new Date(payload.monthStart),
      monthEnd: new Date(payload.monthEnd)
    }))
  }
}
