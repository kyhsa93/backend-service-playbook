import { Injectable } from '@nestjs/common'
import { GetSecretValueCommand, SecretsManagerClient } from '@aws-sdk/client-secrets-manager'

import { SecretService } from '@/common/application/service/secret-service'
import { getAwsEndpoint } from '@/config/aws.config'

@Injectable()
export class SecretServiceImpl extends SecretService {
  private readonly client = new SecretsManagerClient({
    ...(getAwsEndpoint() ? { endpoint: getAwsEndpoint() } : {})
  })

  private readonly cache = new Map<string, { value: string; expiresAt: number }>()
  private readonly ttl = 5 * 60 * 1000

  public async getSecret(secretId: string): Promise<string> {
    const cached = this.cache.get(secretId)
    if (cached && cached.expiresAt > Date.now()) return cached.value

    const result = await this.client.send(
      new GetSecretValueCommand({ SecretId: secretId })
    )
    const value = result.SecretString ?? ''
    this.cache.set(secretId, { value, expiresAt: Date.now() + this.ttl })
    return value
  }
}
