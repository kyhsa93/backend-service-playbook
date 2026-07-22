import { SESClient } from '@aws-sdk/client-ses'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

export const SES_CLIENT = Symbol('SES_CLIENT')

export function createSesClient(): SESClient {
  // Always specify credentials explicitly to avoid the delay from the SDK's default provider chain (IMDS, etc.) lookup.
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
