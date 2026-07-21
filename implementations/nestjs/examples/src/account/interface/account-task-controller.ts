import { Injectable } from '@nestjs/common'

import { ApplyDailyInterestCommand } from '@/account/application/command/apply-daily-interest-command'
import { AccountCommandService } from '@/account/application/service/account-command-service'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

// Task 입력 어댑터(Interface 레이어) — HTTP Controller가 REST 요청을 받아 Command로
// 위임하듯, 여기는 Task 큐 메시지를 받아 Command로 위임한다. 로직 없이 위임만 하고,
// 예외는 그대로 throw해 TaskQueueConsumer가 재시도/DLQ를 판단하게 한다
// (docs/architecture/scheduling.md#taskcontroller--taskconsumer-메서드로-command-실행-interface-레이어).
@Injectable()
export class AccountTaskController {
  constructor(private readonly accountCommandService: AccountCommandService) {}

  @TaskConsumer('account.apply-daily-interest')
  public async applyDailyInterest(payload: { today: string }): Promise<void> {
    // payload는 SQS 메시지를 JSON.parse한 plain object다 — Date 인스턴스가 아니라
    // ISO 문자열이므로 Command 생성 시점에 복원한다(HTTP Controller가
    // new Command({ ...body })로 요청 바디를 Command로 감싸는 것과 같은 패턴).
    await this.accountCommandService.applyDailyInterest(new ApplyDailyInterestCommand({ today: new Date(payload.today) }))
  }
}
