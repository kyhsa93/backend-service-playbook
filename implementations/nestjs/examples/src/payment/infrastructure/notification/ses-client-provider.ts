import { SESClient } from '@aws-sdk/client-ses'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

// The same setup as account/infrastructure/notification/ses-client-provider.ts.
// Following the principle of keeping a Technical Service domain-scoped, the SES client is
// likewise kept Payment BC-only, rather than sharing Account's SES_CLIENT across BCs.
export const PAYMENT_SES_CLIENT = Symbol('PAYMENT_SES_CLIENT')

export function createSesClient(): SESClient {
  return new SESClient({
    region: getAwsRegion(),
    endpoint: getAwsEndpoint(),
    credentials: getAwsCredentials()
  })
}

export const PaymentSesClientProvider: FactoryProvider<SESClient> = {
  provide: PAYMENT_SES_CLIENT,
  useFactory: createSesClient
}
