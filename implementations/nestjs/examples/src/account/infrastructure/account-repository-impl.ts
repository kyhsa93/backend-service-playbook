import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'

@Injectable()
export class AccountRepositoryImpl extends AccountRepository {
  constructor(
    @InjectRepository(AccountEntity) private readonly accountRepo: Repository<AccountEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async findAccounts(query: {
    readonly take: number
    readonly page: number
    readonly accountId?: string
    readonly ownerId?: string
    readonly status?: AccountStatus[]
  }): Promise<{ accounts: Account[]; count: number }> {
    const qb = this.accountRepo.createQueryBuilder('account')
      .orderBy('account.accountId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.accountId) qb.andWhere('account.accountId = :accountId', { accountId: query.accountId })
    if (query.ownerId) qb.andWhere('account.ownerId = :ownerId', { ownerId: query.ownerId })
    if (query.status?.length) qb.andWhere('account.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      accounts: rows.map((row) => new Account({
        accountId: row.accountId,
        ownerId: row.ownerId,
        balance: new Money({ amount: row.amount, currency: row.currency }),
        status: row.status as AccountStatus,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async saveAccount(account: Account): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(AccountEntity, {
      accountId: account.accountId,
      ownerId: account.ownerId,
      amount: account.balance.amount,
      currency: account.balance.currency,
      status: account.status,
      createdAt: account.createdAt
    })

    if (account.pendingTransactions.length > 0) {
      await manager.insert(TransactionEntity, account.pendingTransactions.map((transaction) => ({
        transactionId: transaction.transactionId,
        accountId: transaction.accountId,
        type: transaction.type,
        amount: transaction.amount.amount,
        currency: transaction.amount.currency,
        createdAt: transaction.createdAt
      })))
      account.clearTransactions()
    }

    if (account.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(account.domainEvents)
      account.clearEvents()
    }
  }
}
