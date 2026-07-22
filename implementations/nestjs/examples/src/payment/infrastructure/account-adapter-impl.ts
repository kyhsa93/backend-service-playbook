import { Injectable } from '@nestjs/common'

import { AccountQuery } from '@/account/application/query/account-query'
import { AccountStatus } from '@/account/account-enum'
import { AccountErrorMessage } from '@/account/account-error-message'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'

// The AccountAdapter implementation (ACL). Injects and calls the read service (AccountQuery)
// exported by Account BC, translating Account BC's model·errors into the minimal shape Payment BC uses.
@Injectable()
export class AccountAdapterImpl extends AccountAdapter {
  constructor(private readonly accountQuery: AccountQuery) {
    super()
  }

  public async findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean; balanceAmount: number; currency: string; email: string } | null> {
    try {
      const account = await this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.ownerId })
      return {
        accountId: account.accountId,
        active: account.status === AccountStatus.ACTIVE,
        balanceAmount: account.balance.amount,
        currency: account.balance.currency,
        email: account.email
      }
    } catch (error) {
      if (error instanceof Error && error.message === AccountErrorMessage['계좌를 찾을 수 없습니다.']) return null
      throw error
    }
  }
}
