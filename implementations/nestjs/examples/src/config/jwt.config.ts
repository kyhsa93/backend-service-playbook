import { getAwsEndpoint } from '@/config/aws.config'

export const jwtConfig = async () => {
  // Calls Secrets Manager only in production — every other environment (development/test,
  // etc.) uses only the environment variable, with no network call. Since jest automatically
  // sets NODE_ENV to 'test', if only 'development' were excluded, running tests would also try
  // to reach out to real AWS and break the bootstrap — production must be the only explicitly-selected branch.
  if (process.env.NODE_ENV !== 'production') {
    return {
      jwt: {
        secret: process.env.JWT_SECRET ?? 'dev-secret',
        expiresIn: process.env.JWT_EXPIRES_IN ?? '1h'
      }
    }
  }

  const { SecretsManagerClient, GetSecretValueCommand } = await import('@aws-sdk/client-secrets-manager')
  const client = new SecretsManagerClient({
    ...(getAwsEndpoint() ? { endpoint: getAwsEndpoint() } : {})
  })
  const result = await client.send(new GetSecretValueCommand({ SecretId: 'app/jwt' }))
  const secret = JSON.parse(result.SecretString ?? '{}')

  return {
    jwt: {
      secret: secret.secret,
      expiresIn: process.env.JWT_EXPIRES_IN ?? '1h'
    }
  }
}
