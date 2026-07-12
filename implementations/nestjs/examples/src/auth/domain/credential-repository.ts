import { Credential } from '@/auth/domain/credential'
import { CredentialFindQuery } from '@/auth/domain/credential-find-query'

export abstract class CredentialRepository {
  public abstract findCredentials(query: CredentialFindQuery): Promise<{ credentials: Credential[]; count: number }>
  public abstract saveCredential(credential: Credential): Promise<void>
}
