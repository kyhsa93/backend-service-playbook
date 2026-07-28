import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { Money } from '@/account/domain/money'
import { Transaction, TransactionCategory, TransactionType } from '@/account/domain/transaction'
import { TransactionRepository } from '@/account/domain/transaction-repository'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'

@Injectable()
export class TransactionRepositoryImpl extends TransactionRepository {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async findTransaction(transactionId: string): Promise<Transaction | null> {
    const manager = this.transactionManager.getManager()
    const row = await manager.findOneBy(TransactionEntity, { transactionId })
    if (!row) return null

    return new Transaction({
      transactionId: row.transactionId,
      accountId: row.accountId,
      type: row.type as TransactionType,
      amount: new Money({ amount: row.amount, currency: row.currency }),
      referenceId: row.referenceId ?? undefined,
      merchantName: row.merchantName ?? undefined,
      category: (row.category as TransactionCategory) ?? undefined,
      createdAt: row.createdAt
    })
  }

  public async saveTransaction(transaction: Transaction): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(TransactionEntity, {
      transactionId: transaction.transactionId,
      accountId: transaction.accountId,
      type: transaction.type,
      amount: transaction.amount.amount,
      currency: transaction.amount.currency,
      referenceId: transaction.referenceId ?? null,
      merchantName: transaction.merchantName ?? null,
      category: transaction.category ?? null,
      createdAt: transaction.createdAt
    })
  }
}
