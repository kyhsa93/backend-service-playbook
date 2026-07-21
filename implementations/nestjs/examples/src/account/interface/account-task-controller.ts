import { Injectable } from '@nestjs/common'
import { CommandBus } from '@nestjs/cqrs'

import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

// Task 입력 어댑터(Interface 레이어) — HTTP Controller(account-controller.ts)가 REST
// 요청을 받아 CommandBus.execute()로 위임하듯, 여기는 Task 큐 메시지를 받아 동일하게
// CommandBus로 위임한다. Account는 @nestjs/cqrs 방식(CommandHandler)을 쓰는 도메인이라
// Service 방식의 CommandService를 별도로 두지 않는다(cqrs-pattern.md 적용 기준 참고).
// 로직 없이 위임만 하고, 예외는 그대로 throw해 TaskQueueConsumer가 재시도/DLQ를
// 판단하게 한다(docs/architecture/scheduling.md#taskcontroller--taskconsumer-메서드로-command-실행-interface-레이어).
@Injectable()
export class AccountTaskController {
  constructor(private readonly commandBus: CommandBus) {}

  @TaskConsumer('account.apply-daily-interest')
  public async applyDailyInterest(payload: { today: string }): Promise<void> {
    // payload는 SQS 메시지를 JSON.parse한 plain object다 — Date 인스턴스가 아니라
    // ISO 문자열이므로 Command 생성 시점에 복원한다(HTTP Controller가
    // new Command({ ...body })로 요청 바디를 Command로 감싸는 것과 같은 패턴).
    await this.commandBus.execute<ApplyDailyInterestCommand, number>(
      new ApplyDailyInterestCommand({ today: new Date(payload.today) })
    )
  }
}
