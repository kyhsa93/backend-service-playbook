export abstract class SecretService {
  abstract getSecret(secretId: string): Promise<string>
}
