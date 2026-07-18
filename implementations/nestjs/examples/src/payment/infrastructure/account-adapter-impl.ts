import { Injectable } from '@nestjs/common'

import { AccountQuery } from '@/account/application/query/account-query'
import { AccountStatus } from '@/account/account-enum'
import { AccountErrorMessage } from '@/account/account-error-message'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'

// AccountAdapter의 구현체(ACL). Account BC가 export한 읽기 서비스(AccountQuery)를
// 주입받아 호출하고, Account BC의 모델·에러를 Payment BC가 쓰는 최소 형태로 번역한다.
@Injectable()
export class AccountAdapterImpl extends AccountAdapter {
  constructor(private readonly accountQuery: AccountQuery) {
    super()
  }

  public async findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean; balanceAmount: number; currency: string } | null> {
    try {
      const account = await this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.ownerId })
      return {
        accountId: account.accountId,
        active: account.status === AccountStatus.ACTIVE,
        balanceAmount: account.balance.amount,
        currency: account.balance.currency
      }
    } catch (error) {
      if (error instanceof Error && error.message === AccountErrorMessage['계좌를 찾을 수 없습니다.']) return null
      throw error
    }
  }
}
