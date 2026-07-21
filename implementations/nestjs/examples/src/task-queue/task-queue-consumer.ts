import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common'
import { DeleteMessageCommand, Message, ReceiveMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getTaskQueueUrl } from '@/config/aws.config'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

import { TaskConsumerRegistry } from './task-consumer-registry'

// outbox/outbox-consumer.ts와 같은 모양의 SQS long-polling 백그라운드 루프다. 도메인
// 지식이 없는 공용 Infrastructure 컴포넌트로, taskType(MessageAttributes)으로
// TaskConsumerRegistry에 위임한다.
//
// 메시지 삭제는 핸들러 성공 시에만 — 예외가 발생하면 삭제하지 않아 visibility timeout
// 경과 후 자동 재수신되고, maxReceiveCount(3)를 넘기면 DLQ로 이동한다
// (docs/architecture/scheduling.md#taskqueueconsumer--sqs-폴링).
@Injectable()
export class TaskQueueConsumer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(TaskQueueConsumer.name)
  private running = false

  constructor(
    private readonly registry: TaskConsumerRegistry,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  public onModuleInit(): void {
    this.running = true
    void this.pollLoop()
  }

  public onModuleDestroy(): void {
    this.running = false
  }

  private async pollLoop(): Promise<void> {
    const queueUrl = getTaskQueueUrl()
    while (this.running) {
      try {
        const result = await this.sqs.send(new ReceiveMessageCommand({
          QueueUrl: queueUrl,
          MaxNumberOfMessages: 10,
          MessageAttributeNames: ['taskType'],
          WaitTimeSeconds: 5
        }))

        for (const message of result.Messages ?? []) {
          await this.handleMessage(queueUrl, message)
        }
      } catch (error) {
        this.logger.error({ message: 'Task 큐 수신 실패', error })
        await new Promise((resolve) => setTimeout(resolve, 1000))
      }
    }
  }

  private async handleMessage(queueUrl: string, message: Message): Promise<void> {
    const taskType = message.MessageAttributes?.taskType?.StringValue
    try {
      if (!taskType) throw new Error('taskType 메시지 속성이 없습니다.')
      this.logger.log({ message: 'Task 시작', message_id: message.MessageId, task_type: taskType })
      await this.registry.dispatch(taskType, JSON.parse(message.Body ?? '{}'))
      await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
      this.logger.log({ message: 'Task 완료', message_id: message.MessageId, task_type: taskType })
    } catch (error) {
      this.logger.error({
        message: 'Task 실패 — visibility timeout 경과 후 재수신',
        message_id: message.MessageId,
        task_type: taskType,
        error
      })
      // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
    }
  }
}
