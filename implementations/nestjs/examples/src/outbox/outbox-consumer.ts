import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common'
import { DeleteMessageCommand, Message, ReceiveMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getDomainEventQueueUrl } from '@/config/aws.config'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

// Waits to receive from SQS via long polling (ReceiveMessageCommand's WaitTimeSeconds), and
// when a message arrives, looks up and calls the handler in EventHandlerRegistry by eventType
// (MessageAttributes) — whether it's a Domain Event Handler (application/event/) or an
// Integration Event Controller (interface/integration-event/), both go through this single registry·Consumer.
//
// Handler succeeds → delete the message (ack). Handler fails (or no handler is registered) →
// don't delete — it's automatically redelivered once SQS's visibility timeout passes
// (at-least-once). This is exactly the redelivery that the EventHandler idempotency this repo
// requires (docs/architecture/domain-events.md) assumes.
@Injectable()
export class OutboxConsumer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(OutboxConsumer.name)
  private running = false

  constructor(
    private readonly registry: EventHandlerRegistry,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  // A singleton background loop started exactly once at app bootstrap — it's never recreated per request.
  public onModuleInit(): void {
    this.running = true
    void this.pollLoop()
  }

  // Stops the loop on Graceful Shutdown (app.enableShutdownHooks()). An in-flight
  // ReceiveMessageCommand (up to WaitTimeSeconds) is waited out fully before exiting.
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
        this.logger.error({ message: 'Failed to receive from SQS', error })
        await new Promise((resolve) => setTimeout(resolve, 1000))
      }
    }
  }

  private async handleMessage(queueUrl: string, message: Message): Promise<void> {
    const eventType = message.MessageAttributes?.eventType?.StringValue
    try {
      if (!eventType) throw new Error('The eventType message attribute is missing.')
      await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
      await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
    } catch (error) {
      this.logger.error({ message: 'Failed to process event', event_type: eventType, error })
      // Don't delete — it's re-received and retried after the visibility timeout.
    }
  }
}
