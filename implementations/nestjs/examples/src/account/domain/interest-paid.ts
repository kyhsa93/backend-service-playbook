import { Money } from '@/account/domain/money'

// MoneyDeposited와 형태는 비슷하지만 별개 이벤트다 — "사용자가 입금했다"와 "시스템이
// 이자를 지급했다"는 다른 사실이므로, 알림 문구 등 소비 측이 독립적으로 판단할 수 있게
// 분리한다(account/application/event/interest-paid-handler.ts 참고).
export class InterestPaid {
  public readonly accountId: string
  public readonly email: string
  public readonly transactionId: string
  public readonly amount: Money
  public readonly balanceAfter: Money
  public readonly createdAt: Date

  constructor(params: {
    accountId: string
    email: string
    transactionId: string
    amount: Money
    balanceAfter: Money
    createdAt: Date
  }) {
    this.accountId = params.accountId
    this.email = params.email
    this.transactionId = params.transactionId
    this.amount = params.amount
    this.balanceAfter = params.balanceAfter
    this.createdAt = params.createdAt
  }
}
