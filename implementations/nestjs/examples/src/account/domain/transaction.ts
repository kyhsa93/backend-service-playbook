import { generateId } from '@/common/generate-id'
import { Money } from '@/account/domain/money'

export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL'

export class Transaction {
  public readonly transactionId: string
  public readonly accountId: string
  public readonly type: TransactionType
  public readonly amount: Money
  public readonly createdAt: Date
  // 외부 BC(Payment)의 Integration Event 반응으로 발생한 거래를 다른 BC의 Aggregate
  // ID(paymentId/refundId)로 상관관계 지을 수 있게 하는 선택 필드다. 사용자가 직접
  // 요청한 입금/출금에는 없다(undefined) — Payment 반응 커맨드에서만 채워지며,
  // at-least-once 재수신 시 이 값으로 중복 처리를 막는 Level 2 Ledger 키로 쓰인다
  // (docs/architecture/domain-events.md의 "이벤트 핸들러 멱등성" 참고).
  public readonly referenceId?: string

  constructor(params: {
    transactionId?: string
    accountId: string
    type: TransactionType
    amount: Money
    referenceId?: string
    createdAt?: Date
  }) {
    this.transactionId = params.transactionId ?? generateId()
    this.accountId = params.accountId
    this.type = params.type
    this.amount = params.amount
    this.referenceId = params.referenceId
    this.createdAt = params.createdAt ?? new Date()
  }
}
