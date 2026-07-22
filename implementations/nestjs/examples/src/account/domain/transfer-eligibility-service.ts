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
      return { approved: false, reason: ErrorMessage['The withdrawal account and deposit account cannot be the same.'] }
    }
    if (source.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['Only an active account can make a withdrawal.'] }
    }
    if (target.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['Only an active account can accept a deposit.'] }
    }
    if (source.balance.currency !== target.balance.currency) {
      return { approved: false, reason: ErrorMessage['The currencies do not match.'] }
    }
    if (source.balance.isLessThan(amount)) {
      return { approved: false, reason: ErrorMessage['Insufficient balance.'] }
    }
    return { approved: true }
  }
}
