import { SQSClient } from '@aws-sdk/client-sqs'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

export const SQS_CLIENT = Symbol('SQS_CLIENT')

export function createSqsClient(): SQSClient {
  // credentials를 항상 명시해 SDK 기본 provider chain(IMDS 등) 탐색으로 인한 지연을 피한다.
  // account/infrastructure/notification/ses-client-provider.ts의 createSesClient()와
  // 동일한 구성이다 — OutboxPoller/OutboxConsumer가 이 하나의 클라이언트를 공유한다.
  return new SQSClient({
    region: getAwsRegion(),
    endpoint: getAwsEndpoint(),
    credentials: getAwsCredentials()
  })
}

export const SqsClientProvider: FactoryProvider<SQSClient> = {
  provide: SQS_CLIENT,
  useFactory: createSqsClient
}
