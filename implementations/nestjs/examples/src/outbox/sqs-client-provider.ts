import { SQSClient } from '@aws-sdk/client-sqs'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

export const SQS_CLIENT = Symbol('SQS_CLIENT')

export function createSqsClient(): SQSClient {
  // Always specify credentials explicitly to avoid the delay from the SDK's default provider
  // chain (IMDS, etc.) lookup. The same setup as
  // account/infrastructure/notification/ses-client-provider.ts's createSesClient() —
  // OutboxPoller/OutboxConsumer share this single client.
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
