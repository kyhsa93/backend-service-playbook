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
