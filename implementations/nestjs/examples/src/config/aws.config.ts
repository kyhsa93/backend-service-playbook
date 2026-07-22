export function getAwsRegion(): string {
  return process.env.AWS_REGION ?? 'us-east-1'
}

// Set this value when using LocalStack. Leave it unset for real AWS.
export function getAwsEndpoint(): string | undefined {
  return process.env.AWS_ENDPOINT_URL
}

// In production, returns undefined so the AWS SDK's default credential chain (IAM role) is
// used. Only elsewhere (local/test, LocalStack) does it specify static test/test credentials.
export function getAwsCredentials(): { accessKeyId: string; secretAccessKey: string } | undefined {
  if (process.env.NODE_ENV === 'production') return undefined
  return {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test',
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test'
  }
}

// The shared Domain/Integration Event SQS queue that OutboxPoller publishes to and OutboxConsumer receives from.
export function getDomainEventQueueUrl(): string {
  const url = process.env.SQS_DOMAIN_EVENT_QUEUE_URL
  if (!url) throw new Error('SQS_DOMAIN_EVENT_QUEUE_URL 환경 변수가 설정되지 않았습니다.')
  return url
}

// The shared Task Queue (SQS FIFO) that TaskOutboxRelay publishes to and TaskQueueConsumer
// receives from. A queue separate from the Domain Event queue — "a command (Task): perform X"
// vs. "a fact (Domain Event): X happened" are different consumption-model concepts and aren't
// mixed (see docs/architecture/scheduling.md, the Task vs Domain Event section).
export function getTaskQueueUrl(): string {
  const url = process.env.SQS_TASK_QUEUE_URL
  if (!url) throw new Error('SQS_TASK_QUEUE_URL 환경 변수가 설정되지 않았습니다.')
  return url
}
