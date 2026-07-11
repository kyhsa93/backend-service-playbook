import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { Card } from '@/card/domain/card'
import { CardRepository } from '@/card/domain/card-repository'
import { CardStatus } from '@/card/card-enum'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'

@Injectable()
export class CardRepositoryImpl extends CardRepository {
  constructor(
    @InjectRepository(CardEntity) private readonly cardRepo: Repository<CardEntity>,
    private readonly transactionManager: TransactionManager
  ) {
    super()
  }

  public async findCards(query: {
    readonly take: number
    readonly page: number
    readonly cardId?: string
    readonly ownerId?: string
    readonly accountId?: string
    readonly status?: CardStatus[]
  }): Promise<{ cards: Card[]; count: number }> {
    const qb = this.cardRepo.createQueryBuilder('card')
      .orderBy('card.cardId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.cardId) qb.andWhere('card.cardId = :cardId', { cardId: query.cardId })
    if (query.ownerId) qb.andWhere('card.ownerId = :ownerId', { ownerId: query.ownerId })
    if (query.accountId) qb.andWhere('card.accountId = :accountId', { accountId: query.accountId })
    if (query.status?.length) qb.andWhere('card.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      cards: rows.map((row) => new Card({
        cardId: row.cardId,
        accountId: row.accountId,
        ownerId: row.ownerId,
        brand: row.brand,
        status: row.status as CardStatus,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async saveCard(card: Card): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(CardEntity, {
      cardId: card.cardId,
      accountId: card.accountId,
      ownerId: card.ownerId,
      brand: card.brand,
      status: card.status,
      createdAt: card.createdAt
    })
  }
}
