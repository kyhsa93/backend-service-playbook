import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common'
import { DeleteMessageCommand, Message, ReceiveMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getDomainEventQueueUrl } from '@/config/aws.config'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

// SQS를 long polling(ReceiveMessageCommand의 WaitTimeSeconds)으로 수신 대기하다가
// 메시지를 받으면 eventType(MessageAttributes)으로 EventHandlerRegistry에서 핸들러를
// 찾아 호출한다 — Domain Event Handler(application/event/)든 Integration Event
// Controller(interface/integration-event/)든 이 하나의 레지스트리·Consumer를 거친다.
//
// 핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지
// 않는다 — SQS의 visibility timeout이 지나면 자동 재전달된다(at-least-once). 이
// 저장소가 요구하는 EventHandler 멱등성(docs/architecture/domain-events.md)이 바로
// 이 재전달을 전제한다.
@Injectable()
export class OutboxConsumer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(OutboxConsumer.name)
  private running = false

  constructor(
    private readonly registry: EventHandlerRegistry,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  // 앱 부트스트랩 시 단 한 번 시작되는 싱글턴 백그라운드 루프다 — 요청마다 새로
  // 만들어지지 않는다.
  public onModuleInit(): void {
    this.running = true
    void this.pollLoop()
  }

  // Graceful Shutdown(app.enableShutdownHooks()) 시 루프를 멈춘다. 진행 중인
  // ReceiveMessageCommand(최대 WaitTimeSeconds)는 끝까지 기다린 뒤 종료한다.
  public onModuleDestroy(): void {
    this.running = false
  }

  private async pollLoop(): Promise<void> {
    const queueUrl = getDomainEventQueueUrl()
    while (this.running) {
      try {
        const result = await this.sqs.send(new ReceiveMessageCommand({
          QueueUrl: queueUrl,
          MaxNumberOfMessages: 10,
          MessageAttributeNames: ['eventType'],
          WaitTimeSeconds: 5
        }))

        for (const message of result.Messages ?? []) {
          await this.handleMessage(queueUrl, message)
        }
      } catch (error) {
        this.logger.error({ message: 'SQS 수신 실패', error })
        await new Promise((resolve) => setTimeout(resolve, 1000))
      }
    }
  }

  private async handleMessage(queueUrl: string, message: Message): Promise<void> {
    const eventType = message.MessageAttributes?.eventType?.StringValue
    try {
      if (!eventType) throw new Error('eventType 메시지 속성이 없습니다.')
      await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
      await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
    } catch (error) {
      this.logger.error({ message: '이벤트 처리 실패', event_type: eventType, error })
      // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
    }
  }
}
