import {
  CreateQueueCommand,
  GetQueueAttributesCommand,
  SQSClient
} from '@aws-sdk/client-sqs'

// Serves the same purpose as support/sqs-test-queue.ts (the Domain Event queue) —
// reproduces via SDK calls the same setup as localstack/init-tasks.sh (FIFO + create the DLQ
// first → attach it via RedrivePolicy, maxReceiveCount=3). Since the Testcontainers
// LocalstackContainer doesn't mount the local init script, it's created directly in the test code.
export async function createTaskQueue(endpoint: string): Promise<string> {
  const client = new SQSClient({
    region: 'us-east-1',
    endpoint,
    credentials: { accessKeyId: 'test', secretAccessKey: 'test' }
  })

  const dlq = await client.send(new CreateQueueCommand({
    QueueName: 'task-queue-dlq.fifo',
    Attributes: { FifoQueue: 'true' }
  }))
  const dlqAttributes = await client.send(new GetQueueAttributesCommand({
    QueueUrl: dlq.QueueUrl!,
    AttributeNames: ['QueueArn']
  }))
  const dlqArn = dlqAttributes.Attributes!.QueueArn!

  const mainQueue = await client.send(new CreateQueueCommand({
    QueueName: 'task-queue.fifo',
    Attributes: {
      FifoQueue: 'true',
      RedrivePolicy: JSON.stringify({ deadLetterTargetArn: dlqArn, maxReceiveCount: '3' })
    }
  }))

  return mainQueue.QueueUrl!
}
