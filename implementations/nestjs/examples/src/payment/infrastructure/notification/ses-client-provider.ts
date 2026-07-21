import { SESClient } from '@aws-sdk/client-ses'
import { FactoryProvider } from '@nestjs/common'

import { getAwsCredentials, getAwsEndpoint, getAwsRegion } from '@/config/aws.config'

// account/infrastructure/notification/ses-client-provider.ts와 동일한 구성이다.
// Technical Service를 도메인 스코프로 유지하는 원칙에 따라 SES 클라이언트도 Payment
// BC 전용으로 별도 두고, Account의 SES_CLIENT를 cross-BC로 공유하지 않는다.
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
