import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common'
import { DeleteMessageCommand, Message, ReceiveMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getTaskQueueUrl } from '@/config/aws.config'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

import { TaskConsumerRegistry } from './task-consumer-registry'

// An SQS long-polling background loop shaped like outbox/outbox-consumer.ts. A shared
// Infrastructure component with no domain knowledge, delegating to TaskConsumerRegistry by taskType (MessageAttributes).
//
// Message deletion only on handler success — if an exception occurs, it's not deleted, so it's
// automatically re-received after the visibility timeout elapses, moving to the DLQ once
// maxReceiveCount (3) is exceeded (see docs/architecture/scheduling.md, the TaskQueueConsumer SQS Polling section).
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
      // Don't delete — it's re-received and retried after the visibility timeout.
    }
  }
}
