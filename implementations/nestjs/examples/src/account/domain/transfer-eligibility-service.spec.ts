import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { TransferEligibilityService } from '@/account/domain/transfer-eligibility-service'

// This judgment (same-account check + both accounts active + currency match + sufficient
// balance) can't be verified from either Account instance alone — the fact that it's a rule
// verifiable only by loading both Aggregate instances together is why
// TransferEligibilityService is a Domain Service.
describe('TransferEligibilityService', () => {
  const service = new TransferEligibilityService()

  const createAccount = (params: { accountId: string; status?: AccountStatus; amount?: number; currency?: string }): Account =>
    new Account({
      accountId: params.accountId,
      ownerId: 'owner-1',
      email: 'owner-1@example.com',
      balance: new Money({ amount: params.amount ?? 10000, currency: params.currency ?? 'KRW' }),
      status: params.status ?? AccountStatus.ACTIVE
    })

  it('evaluate_when_all_conditions_are_met_then_approves', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_the_withdrawal_and_deposit_accounts_are_the_same_then_rejects', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-1', amount: 10000 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('The withdrawal account and deposit account cannot be the same.')
  })

  it('evaluate_when_the_withdrawal_account_is_inactive_then_rejects', () => {
    const source = createAccount({ accountId: 'account-1', status: AccountStatus.SUSPENDED, amount: 10000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('Only an active account can make a withdrawal.')
  })

  it('evaluate_when_the_deposit_account_is_inactive_then_rejects', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-2', status: AccountStatus.CLOSED, amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('Only an active account can accept a deposit.')
  })

  it('evaluate_when_the_currencies_do_not_match_then_rejects', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000, currency: 'KRW' })
    const target = createAccount({ accountId: 'account-2', amount: 0, currency: 'USD' })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('The currencies do not match.')
  })

  it('evaluate_when_the_withdrawal_account_balance_is_insufficient_then_rejects', () => {
    const source = createAccount({ accountId: 'account-1', amount: 1000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('Insufficient balance.')
  })
})
