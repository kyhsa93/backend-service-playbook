import { Injectable } from '@nestjs/common'
import { ModuleRef } from '@nestjs/core'

import { getTaskHandler } from './task-consumer.decorator'

// taskType으로 등록된 Task Controller 메서드를 찾아 호출한다. Task Controller는 반드시
// 자기 도메인 모듈의 providers에 등록되어 있어야 ModuleRef가 인스턴스를 해결할 수 있다
// (docs/architecture/scheduling.md#모듈-등록).
//
// 두 Task(account.apply-daily-interest, payment.send-card-statements) 모두 상태 기반의
// 본질적 멱등성(Level 1)으로 설계되어 있어 이 레지스트리는 별도 ledger를 두지 않는다 —
// 부작용이 큰 Task가 추가되면 EventHandlerRegistry의 방식과 동일하게 Level 2(ledger)를
// 여기에 얹을 수 있다(scheduling.md#멱등성).
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
