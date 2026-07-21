import { Injectable } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { SendCardStatementsCommand } from '@/payment/application/command/send-card-statements-command'

// account/application/service/account-command-service.ts와 같은 목적의 얇은 래퍼 —
// Task Controller가 요구하는 "CommandService 주입" 계약을 만족시키며 CommandBus로
// 위임만 한다.
@Injectable()
export class PaymentCommandService {
  constructor(private readonly commandBus: CommandBus) {}

  public async sendCardStatements(command: SendCardStatementsCommand): Promise<number> {
    return this.commandBus.execute<SendCardStatementsCommand, number>(command)
  }
}
