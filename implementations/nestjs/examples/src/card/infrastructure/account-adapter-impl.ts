import { Injectable } from '@nestjs/common'

import { AccountQuery } from '@/account/application/query/account-query'
import { AccountStatus } from '@/account/account-enum'
import { AccountErrorMessage } from '@/account/account-error-message'
import { AccountAdapter } from '@/card/application/adapter/account-adapter'

// AccountAdapter의 구현체(ACL). 외부 도메인 모듈(AccountModule)이 export한 읽기 서비스
// (AccountQuery)를 주입받아 호출하고, Account BC의 모델·에러를 Card BC가 쓰는 최소 형태로
// 번역한다. Account의 Repository/도메인 객체는 직접 참조하지 않는다.
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
      // 상류의 "계좌 없음" 신호를 Card 도메인이 이해하는 null로 번역한다 (오염 방지).
      if (error instanceof Error && error.message === AccountErrorMessage['계좌를 찾을 수 없습니다.']) return null
      throw error
    }
  }
}
