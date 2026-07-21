import { Injectable } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'

// Task Controller(interface/account-task-controller.ts)가 주입받는 얇은 Command Service다.
// HTTP Controller가 이미 CommandBus.execute()를 직접 호출하는 이 코드베이스의 CQRS
// 컨벤션과 Task Controller의 요구사항("CommandService를 주입해 위임")을 함께 만족시키기
// 위한 래퍼로, 비즈니스 로직은 없고 CommandBus로 위임만 한다
// (docs/architecture/scheduling.md#taskcontroller--taskconsumer-메서드로-command-실행-interface-레이어).
@Injectable()
export class AccountCommandService {
  constructor(private readonly commandBus: CommandBus) {}

  public async applyDailyInterest(command: ApplyDailyInterestCommand): Promise<number> {
    return this.commandBus.execute<ApplyDailyInterestCommand, number>(command)
  }
}
