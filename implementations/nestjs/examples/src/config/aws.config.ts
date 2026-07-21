export function getAwsRegion(): string {
  return process.env.AWS_REGION ?? 'us-east-1'
}

// LocalStack 사용 시 이 값을 설정한다. 실제 AWS에서는 비워 둔다.
export function getAwsEndpoint(): string | undefined {
  return process.env.AWS_ENDPOINT_URL
}

// 운영(production)에서는 undefined를 반환해 AWS SDK 기본 자격증명 체인(IAM 역할)을
// 쓰게 한다. 그 외(로컬/테스트, LocalStack)만 정적 test/test 자격증명을 명시한다.
export function getAwsCredentials(): { accessKeyId: string; secretAccessKey: string } | undefined {
  if (process.env.NODE_ENV === 'production') return undefined
  return {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test',
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test'
  }
}

// OutboxPoller가 발행하고 OutboxConsumer가 수신하는 공유 Domain/Integration Event SQS 큐.
export function getDomainEventQueueUrl(): string {
  const url = process.env.SQS_DOMAIN_EVENT_QUEUE_URL
  if (!url) throw new Error('SQS_DOMAIN_EVENT_QUEUE_URL 환경 변수가 설정되지 않았습니다.')
  return url
}

// TaskOutboxRelay가 발행하고 TaskQueueConsumer가 수신하는 공유 Task Queue(SQS FIFO).
// Domain Event 큐와 별도 큐다 — "명령(Task): X를 수행하라" vs "사실(Domain Event): X가
// 일어났다"는 소비 모델이 다른 개념이라 섞지 않는다(docs/architecture/scheduling.md#task-vs-domain-event).
export function getTaskQueueUrl(): string {
  const url = process.env.SQS_TASK_QUEUE_URL
  if (!url) throw new Error('SQS_TASK_QUEUE_URL 환경 변수가 설정되지 않았습니다.')
  return url
}
