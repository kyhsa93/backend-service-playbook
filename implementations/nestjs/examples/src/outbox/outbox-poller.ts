import { Inject, Injectable, Logger } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'
import { SendMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getDomainEventQueueUrl } from '@/config/aws.config'
import { TransactionManager } from '@/database/transaction-manager'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

// Outbox 테이블 → SQS 발행만 담당한다("DB에 쌓인 이벤트를 큐로 실어 나른다"). 어떤
// EventHandler도 직접 호출하지 않는다 — 그건 OutboxConsumer의 몫이다.
//
// Command Service/Handler는 이 클래스를 전혀 참조하지 않는다. 이 클래스가 독립적으로
// 주기 실행되는 것 자체가 "저장 직후 같은 프로세스 안에서 동기 드레인"을 제거하는
// 핵심이다 — Command가 저장을 커밋하고 응답을 반환한 뒤에도, 이 이벤트가 언제 큐로
// 나가는지는 다음 tick(최대 1초 뒤)까지 알 수 없다.
//
// processed=true는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는
// 뜻이다 — 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility
// timeout + DLQ가 담당한다(docs/architecture/domain-events.md 참고).
@Injectable()
export class OutboxPoller {
  private readonly logger = new Logger(OutboxPoller.name)
  // 이전 tick의 드레인이 아직 끝나지 않았으면 겹쳐 실행하지 않는다 — 폴링 주기(1초)보다
  // 처리해야 할 행이 많아 오래 걸리는 경우를 대비한다.
  private isPolling = false

  constructor(
    private readonly transactionManager: TransactionManager,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  @Interval(1000)
  public async poll(): Promise<void> {
    if (this.isPolling) return
    this.isPolling = true
    try {
      await this.drainOnce()
    } catch (error) {
      this.logger.error({ message: 'Outbox 폴링 실패', error })
    } finally {
      this.isPolling = false
    }
  }

  private async drainOnce(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.find(OutboxEntity, {
      where: { processed: false },
      order: { createdAt: 'ASC' },
      take: 100
    })
    if (rows.length === 0) return

    const queueUrl = getDomainEventQueueUrl()
    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: queueUrl,
          MessageBody: row.payload,
          MessageAttributes: {
            eventType: { DataType: 'String', StringValue: row.eventType }
          }
        }))
        await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
      } catch (error) {
        // 발행 실패 행은 processed=false로 남겨 다음 tick에서 재시도한다.
        this.logger.error({ message: 'SQS 발행 실패', event_type: row.eventType, event_id: row.eventId, error })
      }
    }
  }
}
