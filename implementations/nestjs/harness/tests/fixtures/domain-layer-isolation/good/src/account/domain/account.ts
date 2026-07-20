import { generateId } from '@/common/generate-id'
import { AccountErrorMessage } from '@/account/account-error-message'

export class Account {
  public readonly accountId: string
  private _status: string

  constructor(params: { accountId?: string; status: string }) {
    this.accountId = params.accountId ?? generateId()
    this._status = params.status
  }

  get status(): string {
    return this._status
  }

  public suspend(): void {
    if (this._status !== 'ACTIVE') throw new Error(AccountErrorMessage['활성 상태의 계좌만 정지할 수 있습니다.'])
    this._status = 'SUSPENDED'
  }
}
