import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

export interface TransferDecision {
  readonly approved: boolean
  readonly reason?: string
}

// A Domain Service — a plain class with no framework decorators (it's not registered in the
// NestJS DI container either. The Application layer creates it directly with `new` when
// needed, for the same reason as RefundEligibilityService).
//
// The judgment "are the withdrawal and deposit accounts different, are both active, is the
// currency the same, and does the withdrawal account have a sufficient balance" can't be made
// from either Account alone — both Aggregate instances must be loaded and compared side by
// side (see the root docs/architecture/domain-service.md).
//
// The rejection reason isn't free text — it's the exact same AccountErrorMessage string that
// gets thrown when a real user directly calls deposit/withdraw. Unlike Refund, Transfer has no
// persistent Aggregate of its own (there's nothing to save a rejection to as a REJECTED
// state), so a rejection must go straight out as an HTTP error (400), and that error must be
// indistinguishable, from the client's perspective, from a direct call's error.
export class TransferEligibilityService {
  public evaluate(source: Account, target: Account, amount: Money): TransferDecision {
    if (source.accountId === target.accountId) {
      return { approved: false, reason: ErrorMessage['출금 계좌와 입금 계좌가 동일할 수 없습니다.'] }
    }
    if (source.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['활성 상태의 계좌만 출금할 수 있습니다.'] }
    }
    if (target.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'] }
    }
    if (source.balance.currency !== target.balance.currency) {
      return { approved: false, reason: ErrorMessage['통화가 일치하지 않습니다.'] }
    }
    if (source.balance.isLessThan(amount)) {
      return { approved: false, reason: ErrorMessage['잔액이 부족합니다.'] }
    }
    return { approved: true }
  }
}
