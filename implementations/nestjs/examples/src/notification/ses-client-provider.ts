import { SESClient } from '@aws-sdk/client-ses'
import { FactoryProvider } from '@nestjs/common'

export const SES_CLIENT = Symbol('SES_CLIENT')

export function createSesClient(): SESClient {
  // credentials를 항상 명시해 SDK 기본 provider chain(IMDS 등) 탐색으로 인한 지연을 피한다.
  return new SESClient({
    region: process.env.AWS_REGION ?? 'us-east-1',
    endpoint: process.env.AWS_ENDPOINT_URL,
    credentials: {
      accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? 'test',
      secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? 'test'
    }
  })
}

export const SesClientProvider: FactoryProvider<SESClient> = {
  provide: SES_CLIENT,
  useFactory: createSesClient
}
