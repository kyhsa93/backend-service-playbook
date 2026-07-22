import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { CardQuery } from '@/card/application/query/card-query'
import { GetCardResult } from '@/card/application/query/card-result'
import { CardStatus } from '@/card/card-enum'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'
import { CardErrorMessage as ErrorMessage } from '@/card/card-error-message'

@Injectable()
export class CardQueryImpl extends CardQuery {
  constructor(
    @InjectRepository(CardEntity) private readonly cardRepo: Repository<CardEntity>
  ) {
    super()
  }

  public async getCard(param: { cardId: string; ownerId: string }): Promise<GetCardResult> {
    const row = await this.cardRepo.createQueryBuilder('card')
      .where('card.cardId = :cardId', { cardId: param.cardId })
      .andWhere('card.ownerId = :ownerId', { ownerId: param.ownerId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['Card not found.'])

    return {
      cardId: row.cardId,
      accountId: row.accountId,
      ownerId: row.ownerId,
      brand: row.brand,
      status: row.status,
      createdAt: row.createdAt
    }
  }

  public async getActiveCards(): Promise<{ cardId: string; accountId: string; ownerId: string }[]> {
    const rows = await this.cardRepo.find({ where: { status: CardStatus.ACTIVE } })
    return rows.map((row) => ({ cardId: row.cardId, accountId: row.accountId, ownerId: row.ownerId }))
  }
}
