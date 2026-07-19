import {
  CreateQueueCommand,
  GetQueueAttributesCommand,
  SQSClient
} from '@aws-sdk/client-sqs'

// E2E 테스트용 LocalStack SQS에 OutboxPoller/OutboxConsumer가 쓸 domain-events 큐 +
// DLQ를 만든다. localstack/init-sqs.sh(docker-compose 로컬 개발용)와 동일한 구성
// (DLQ 우선 생성 → RedrivePolicy로 연결, maxReceiveCount=3)을 SDK 호출로 재현한다 —
// Testcontainers LocalstackContainer는 로컬 init 스크립트를 마운트하지 않으므로
// 테스트 코드에서 직접 만든다.
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
