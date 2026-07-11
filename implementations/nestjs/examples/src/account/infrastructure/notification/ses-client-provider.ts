import { SESClient } from '@aws-sdk/client-ses'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

export const SES_CLIENT = Symbol('SES_CLIENT')

export function createSesClient(): SESClient {
  // credentials를 항상 명시해 SDK 기본 provider chain(IMDS 등) 탐색으로 인한 지연을 피한다.
  return new SESClient({
    region: getAwsRegion(),
    endpoint: getAwsEndpoint(),
    credentials: getAwsCredentials()
  })
}

export const SesClientProvider: FactoryProvider<SESClient> = {
  provide: SES_CLIENT,
  useFactory: createSesClient
}
