import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountResult, GetTransactionsResult } from '@/account/application/query/account-result'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

@Injectable()
export class AccountQueryImpl extends AccountQuery {
  constructor(
    @InjectRepository(AccountEntity) private readonly accountRepo: Repository<AccountEntity>,
    @InjectRepository(TransactionEntity) private readonly transactionRepo: Repository<TransactionEntity>
  ) {
    super()
  }

  public async getAccount(param: { accountId: string; ownerId: string }): Promise<GetAccountResult> {
    const row = await this.accountRepo.createQueryBuilder('account')
      .where('account.accountId = :accountId', { accountId: param.accountId })
      .andWhere('account.ownerId = :ownerId', { ownerId: param.ownerId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    return {
      accountId: row.accountId,
      ownerId: row.ownerId,
      balance: { amount: row.amount, currency: row.currency },
      status: row.status,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt
    }
  }

  public async getTransactions(query: {
    accountId: string
    ownerId: string
    take: number
    page: number
  }): Promise<GetTransactionsResult> {
    const account = await this.accountRepo.createQueryBuilder('account')
      .where('account.accountId = :accountId', { accountId: query.accountId })
      .andWhere('account.ownerId = :ownerId', { ownerId: query.ownerId })
      .getOne()
    if (!account) throw new Error(ErrorMessage['계좌를 찾을 수 없습니다.'])

    const qb = this.transactionRepo.createQueryBuilder('transaction')
      .where('transaction.accountId = :accountId', { accountId: query.accountId })
      .orderBy('transaction.createdAt', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    const [rows, count] = await qb.getManyAndCount()

    return {
      transactions: rows.map((row) => ({
        transactionId: row.transactionId,
        type: row.type,
        amount: { amount: row.amount, currency: row.currency },
        createdAt: row.createdAt
      })),
      count
    }
  }
}
