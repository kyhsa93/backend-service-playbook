export const jwtConfig = async () => {
  // 운영(production)에서만 Secrets Manager를 호출한다 — 그 외(development/test 등)는
  // 네트워크 호출 없이 환경 변수만 사용한다. jest는 NODE_ENV를 자동으로 'test'로
  // 설정하므로, 'development'만 예외 처리하면 테스트 실행 시에도 실제 AWS로 나가려
  // 시도해 부트스트랩이 깨진다 — 반드시 production만 명시적으로 선택해야 한다.
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
    ...(process.env.AWS_ENDPOINT ? { endpoint: process.env.AWS_ENDPOINT } : {})
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
