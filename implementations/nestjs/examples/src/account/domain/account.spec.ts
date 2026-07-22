import { Account } from '@/account/domain/account'
import { AccountClosed } from '@/account/domain/account-closed'
import { AccountCreated } from '@/account/domain/account-created'
import { AccountReactivated } from '@/account/domain/account-reactivated'
import { AccountStatus } from '@/account/account-enum'
import { AccountSuspended } from '@/account/domain/account-suspended'
import { Money } from '@/account/domain/money'
import { MoneyDeposited } from '@/account/domain/money-deposited'
import { MoneyWithdrawn } from '@/account/domain/money-withdrawn'

describe('Account', () => {
  const createActiveAccount = (balance = 0): Account => {
    const account = new Account({
      ownerId: 'owner-1',
      email: 'owner1@example.com',
      balance: new Money({ amount: balance, currency: 'KRW' }),
      status: AccountStatus.ACTIVE
    })
    account.clearEvents()
    return account
  }

  describe('create', () => {
    it('create_when_valid_input_then_created_ACTIVE_with_0_balance_and_publishes_AccountCreated_event', () => {
      const account = Account.create({ ownerId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })

      expect(account.status).toBe(AccountStatus.ACTIVE)
      expect(account.balance.amount).toBe(0)
      expect(account.domainEvents).toHaveLength(1)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountCreated)
    })
  })

  describe('deposit', () => {
    it('deposit_when_positive_amount_on_active_account_then_balance_increases_and_publishes_MoneyDeposited_event', () => {
      const account = createActiveAccount(1000)

      const transaction = account.deposit(new Money({ amount: 500, currency: 'KRW' }))

      expect(account.balance.amount).toBe(1500)
      expect(transaction.type).toBe('DEPOSIT')
      expect(account.pendingTransactions).toHaveLength(1)
      expect(account.domainEvents).toHaveLength(1)
      expect(account.domainEvents[0]).toBeInstanceOf(MoneyDeposited)
    })

    it('deposit_when_account_is_suspended_then_throws', () => {
      const account = createActiveAccount()
      account.suspend()

      expect(() => account.deposit(new Money({ amount: 500, currency: 'KRW' })))
        .toThrow('Only an active account can accept a deposit.')
    })

    it('deposit_when_amount_is_0_or_less_then_throws', () => {
      const account = createActiveAccount()

      expect(() => account.deposit(new Money({ amount: 0, currency: 'KRW' })))
        .toThrow('The amount must be greater than 0.')
    })
  })

  describe('withdraw', () => {
    it('withdraw_when_amount_is_within_balance_on_active_account_then_balance_decreases_and_publishes_MoneyWithdrawn_event', () => {
      const account = createActiveAccount(1000)

      const transaction = account.withdraw(new Money({ amount: 400, currency: 'KRW' }))

      expect(account.balance.amount).toBe(600)
      expect(transaction.type).toBe('WITHDRAWAL')
      expect(account.domainEvents[0]).toBeInstanceOf(MoneyWithdrawn)
    })

    it('withdraw_when_account_is_suspended_then_throws', () => {
      const account = createActiveAccount(1000)
      account.suspend()

      expect(() => account.withdraw(new Money({ amount: 100, currency: 'KRW' })))
        .toThrow('Only an active account can make a withdrawal.')
    })

    it('withdraw_when_amount_is_0_or_less_then_throws', () => {
      const account = createActiveAccount(1000)

      expect(() => account.withdraw(new Money({ amount: 0, currency: 'KRW' })))
        .toThrow('The amount must be greater than 0.')
    })

    it('withdraw_when_amount_exceeds_balance_then_throws', () => {
      const account = createActiveAccount(100)

      expect(() => account.withdraw(new Money({ amount: 200, currency: 'KRW' })))
        .toThrow('Insufficient balance.')
    })
  })

  describe('applyInterest', () => {
    const today = new Date('2026-07-20T00:00:00.000Z')
    const yesterday = new Date('2026-07-19T00:00:00.000Z')

    it('applyInterest_when_active_account_has_interest_greater_than_0_then_balance_increases_and_publishes_INTEREST_transaction_and_InterestPaid_event', () => {
      const account = createActiveAccount(1_000_000)

      const transaction = account.applyInterest(today)

      expect(transaction).not.toBeNull()
      expect(transaction?.type).toBe('INTEREST')
      expect(transaction?.amount.amount).toBe(100)
      expect(account.balance.amount).toBe(1_000_100)
      expect(account.lastInterestPaidAt).toEqual(today)
      expect(account.domainEvents[0].constructor.name).toBe('InterestPaid')
    })

    it('applyInterest_when_computed_interest_is_0_then_returns_null_balance_unchanged_but_lastInterestPaidAt_is_updated', () => {
      const account = createActiveAccount(100)

      const transaction = account.applyInterest(today)

      expect(transaction).toBeNull()
      expect(account.balance.amount).toBe(100)
      expect(account.lastInterestPaidAt).toEqual(today)
      expect(account.domainEvents).toHaveLength(0)
    })

    it('applyInterest_when_called_twice_on_the_same_day_then_second_call_returns_null_and_balance_is_not_double_incremented', () => {
      const account = createActiveAccount(1_000_000)
      account.applyInterest(today)
      account.clearEvents()
      account.clearTransactions()

      const secondCall = account.applyInterest(today)

      expect(secondCall).toBeNull()
      expect(account.balance.amount).toBe(1_000_100)
      expect(account.domainEvents).toHaveLength(0)
    })

    it('applyInterest_when_already_paid_yesterday_and_called_again_today_then_pays_again', () => {
      const account = new Account({
        ownerId: 'owner-1',
        email: 'owner1@example.com',
        balance: new Money({ amount: 1_000_000, currency: 'KRW' }),
        status: AccountStatus.ACTIVE,
        lastInterestPaidAt: yesterday
      })

      const transaction = account.applyInterest(today)

      expect(transaction).not.toBeNull()
      expect(account.balance.amount).toBe(1_000_100)
    })

    it('applyInterest_when_account_is_suspended_then_returns_null_and_lastInterestPaidAt_is_unchanged', () => {
      const account = createActiveAccount(1_000_000)
      account.suspend()

      const transaction = account.applyInterest(today)

      expect(transaction).toBeNull()
      expect(account.lastInterestPaidAt).toBeNull()
    })
  })

  describe('suspend', () => {
    it('suspend_when_account_is_active_then_becomes_SUSPENDED_and_publishes_AccountSuspended_event', () => {
      const account = createActiveAccount()

      account.suspend()

      expect(account.status).toBe(AccountStatus.SUSPENDED)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountSuspended)
    })

    it('suspend_when_account_is_already_suspended_then_throws', () => {
      const account = createActiveAccount()
      account.suspend()

      expect(() => account.suspend()).toThrow('Only an active account can be suspended.')
    })
  })

  describe('reactivate', () => {
    it('reactivate_when_account_is_suspended_then_becomes_ACTIVE_and_publishes_AccountReactivated_event', () => {
      const account = createActiveAccount()
      account.suspend()
      account.clearEvents()

      account.reactivate()

      expect(account.status).toBe(AccountStatus.ACTIVE)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountReactivated)
    })

    it('reactivate_when_account_is_active_then_throws', () => {
      const account = createActiveAccount()

      expect(() => account.reactivate()).toThrow('Only a suspended account can be reactivated.')
    })
  })

  describe('close', () => {
    it('close_when_balance_is_0_then_becomes_CLOSED_and_publishes_AccountClosed_event', () => {
      const account = createActiveAccount(0)

      account.close()

      expect(account.status).toBe(AccountStatus.CLOSED)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountClosed)
    })

    it('close_when_balance_is_not_0_then_throws', () => {
      const account = createActiveAccount(1000)

      expect(() => account.close()).toThrow('An account with a non-zero balance cannot be closed.')
    })

    it('close_when_account_is_already_closed_then_throws', () => {
      const account = createActiveAccount(0)
      account.close()

      expect(() => account.close()).toThrow('The account is already closed.')
    })
  })

  describe('clearEvents/clearTransactions', () => {
    it('clearEvents_when_called_then_domainEvents_is_emptied', () => {
      const account = Account.create({ ownerId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })
      account.clearEvents()
      expect(account.domainEvents).toHaveLength(0)
    })

    it('clearTransactions_when_called_then_pendingTransactions_is_emptied', () => {
      const account = createActiveAccount(1000)
      account.deposit(new Money({ amount: 100, currency: 'KRW' }))
      account.clearTransactions()
      expect(account.pendingTransactions).toHaveLength(0)
    })
  })
})
