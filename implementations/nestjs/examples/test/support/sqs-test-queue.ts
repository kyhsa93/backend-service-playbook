import {
  CreateQueueCommand,
  GetQueueAttributesCommand,
  SQSClient
} from '@aws-sdk/client-sqs'

// Creates the domain-events queue + DLQ that OutboxPoller/OutboxConsumer use, on the LocalStack
// SQS for E2E tests. Reproduces via SDK calls the same setup as localstack/init-sqs.sh (for
// local docker-compose development) — create the DLQ first → attach it via RedrivePolicy,
// maxReceiveCount=3 — since the Testcontainers LocalstackContainer doesn't mount the local init
// script, it's created directly in the test code.
export async function createDomainEventQueue(endpoint: string): Promise<string> {
  const client = new SQSClient({
    region: 'us-east-1',
    endpoint,
    credentials: { accessKeyId: 'test', secretAccessKey: 'test' }
  })

  const dlq = await client.send(new CreateQueueCommand({ QueueName: 'domain-events-dlq' }))
  const dlqAttributes = await client.send(new GetQueueAttributesCommand({
    QueueUrl: dlq.QueueUrl!,
    AttributeNames: ['QueueArn']
  }))
  const dlqArn = dlqAttributes.Attributes!.QueueArn!

  const mainQueue = await client.send(new CreateQueueCommand({
    QueueName: 'domain-events',
    Attributes: {
      RedrivePolicy: JSON.stringify({ deadLetterTargetArn: dlqArn, maxReceiveCount: '3' })
    }
  }))

  return mainQueue.QueueUrl!
}
