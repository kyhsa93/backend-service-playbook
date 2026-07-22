import { Injectable } from '@nestjs/common'

import { AccountQuery } from '@/account/application/query/account-query'
import { AccountStatus } from '@/account/account-enum'
import { AccountErrorMessage } from '@/account/account-error-message'
import { AccountAdapter } from '@/card/application/adapter/account-adapter'

// The AccountAdapter implementation (ACL). Injects and calls the read service (AccountQuery)
// exported by the external domain module (AccountModule), translating Account BC's model·errors
// into the minimal shape Card BC uses. Never references Account's Repository/domain objects directly.
@Injectable()
export class AccountAdapterImpl extends AccountAdapter {
  constructor(private readonly accountQuery: AccountQuery) {
    super()
  }

  public async findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean } | null> {
    try {
      const account = await this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.ownerId })
      return { accountId: account.accountId, active: account.status === AccountStatus.ACTIVE }
    } catch (error) {
      // Translate the upstream "account not found" signal into a null the Card domain understands (preventing model pollution).
      if (error instanceof Error && error.message === AccountErrorMessage['계좌를 찾을 수 없습니다.']) return null
      throw error
    }
  }
}
