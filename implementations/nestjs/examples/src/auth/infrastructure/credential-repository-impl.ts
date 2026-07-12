import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { Credential } from '@/auth/domain/credential'
import { CredentialFindQuery } from '@/auth/domain/credential-find-query'
import { CredentialRepository } from '@/auth/domain/credential-repository'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'

@Injectable()
export class CredentialRepositoryImpl extends CredentialRepository {
  constructor(
    @InjectRepository(CredentialEntity) private readonly credentialRepo: Repository<CredentialEntity>
  ) {
    super()
  }

  public async findCredentials(query: CredentialFindQuery): Promise<{ credentials: Credential[]; count: number }> {
    const qb = this.credentialRepo.createQueryBuilder('credential')
      .orderBy('credential.userId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.userId) qb.andWhere('credential.userId = :userId', { userId: query.userId })

    const [rows, count] = await qb.getManyAndCount()

    return {
      credentials: rows.map((row) => new Credential({
        credentialId: row.credentialId,
        userId: row.userId,
        passwordHash: row.passwordHash,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async saveCredential(credential: Credential): Promise<void> {
    await this.credentialRepo.save({
      credentialId: credential.credentialId,
      userId: credential.userId,
      passwordHash: credential.passwordHash,
      createdAt: credential.createdAt
    })
  }
}
