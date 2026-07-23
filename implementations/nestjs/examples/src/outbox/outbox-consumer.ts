import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common'
import { DeleteMessageCommand, Message, ReceiveMessageCommand, SQSClient } from '@aws-sdk/client-sqs'
import { context as otelContext, trace } from '@opentelemetry/api'

import { CorrelationIdStore } from '@/common/correlation-id-store'
import { generateId } from '@/common/generate-id'
import { getDomainEventQueueUrl } from '@/config/aws.config'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'
import { contextWithTraceParent } from '@/outbox/trace-context'

// Named "outbox" the same way @opentelemetry/instrumentation-http names its own spans after its
// package — every span this file starts shows up as this Consumer's contribution to whatever
// trace the originating HTTP request (or an earlier event) started.
const tracer = trace.getTracer('outbox')

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
          MessageAttributeNames: ['eventType', 'traceparent'],
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
    // Re-hydrates the span context OutboxPoller forwarded as the "traceparent" message
    // attribute (trace-context.ts) — this is what makes the event-processing side show up in
    // the same trace as the HTTP request that originally wrote the outbox row
    // (observability.md). A row with no traceparent (e.g. a Task Queue-driven event) leaves the
    // active context unchanged, and startActiveSpan still works — it just starts a new,
    // disconnected trace rather than erroring.
    const traceParent = message.MessageAttributes?.traceparent?.StringValue
    const extractedContext = contextWithTraceParent(traceParent)

    await otelContext.with(extractedContext, () =>
      tracer.startActiveSpan(`outbox.consume ${eventType ?? 'unknown'}`, async (span) => {
        // Folded into CorrelationIdStore (rather than read ad hoc per log call) so every log a
        // Handler emits while processing this message — same mechanism as the HTTP request
        // path in correlation-id.middleware.ts — includes trace_id with no separate lookup.
        // correlationId is freshly generated here since there's no original request
        // Correlation ID to inherit — only the trace, not the correlation scope, crosses the
        // Outbox hop.
        const traceId = span.spanContext().traceId
        try {
          await CorrelationIdStore.run({ correlationId: generateId(), traceId }, async () => {
            if (!eventType) throw new Error('The eventType message attribute is missing.')
            await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
            await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
          })
        } catch (error) {
          this.logger.error({ message: 'Failed to process event', event_type: eventType, trace_id: traceId, error })
          // Don't delete — it's re-received and retried after the visibility timeout.
        } finally {
          span.end()
        }
      })
    )
  }
}
