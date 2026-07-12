import { generateId } from '@/common/generate-id'

export class Credential {
  public readonly credentialId: string
  public readonly userId: string
  public readonly passwordHash: string
  public readonly createdAt: Date

  constructor(params: { credentialId?: string; userId: string; passwordHash: string; createdAt?: Date }) {
    this.credentialId = params.credentialId ?? generateId()
    this.userId = params.userId
    this.passwordHash = params.passwordHash
    this.createdAt = params.createdAt ?? new Date()
  }

  public static create(params: { userId: string; passwordHash: string }): Credential {
    return new Credential(params)
  }
}
