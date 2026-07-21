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
    it('create_when_정상_입력_then_ACTIVE_상태와_0원_잔액으로_생성되고_AccountCreated_이벤트가_발행된다', () => {
      const account = Account.create({ ownerId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })

      expect(account.status).toBe(AccountStatus.ACTIVE)
      expect(account.balance.amount).toBe(0)
      expect(account.domainEvents).toHaveLength(1)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountCreated)
    })
  })

  describe('deposit', () => {
    it('deposit_when_활성_계좌에_양수_금액_then_잔액이_증가하고_MoneyDeposited_이벤트가_발행된다', () => {
      const account = createActiveAccount(1000)

      const transaction = account.deposit(new Money({ amount: 500, currency: 'KRW' }))

      expect(account.balance.amount).toBe(1500)
      expect(transaction.type).toBe('DEPOSIT')
      expect(account.pendingTransactions).toHaveLength(1)
      expect(account.domainEvents).toHaveLength(1)
      expect(account.domainEvents[0]).toBeInstanceOf(MoneyDeposited)
    })

    it('deposit_when_정지된_계좌_then_에러를_throw한다', () => {
      const account = createActiveAccount()
      account.suspend()

      expect(() => account.deposit(new Money({ amount: 500, currency: 'KRW' })))
        .toThrow('활성 상태의 계좌만 입금할 수 있습니다.')
    })

    it('deposit_when_금액이_0이하_then_에러를_throw한다', () => {
      const account = createActiveAccount()

      expect(() => account.deposit(new Money({ amount: 0, currency: 'KRW' })))
        .toThrow('금액은 0보다 커야 합니다.')
    })
  })

  describe('withdraw', () => {
    it('withdraw_when_활성_계좌에_잔액_이내_금액_then_잔액이_감소하고_MoneyWithdrawn_이벤트가_발행된다', () => {
      const account = createActiveAccount(1000)

      const transaction = account.withdraw(new Money({ amount: 400, currency: 'KRW' }))

      expect(account.balance.amount).toBe(600)
      expect(transaction.type).toBe('WITHDRAWAL')
      expect(account.domainEvents[0]).toBeInstanceOf(MoneyWithdrawn)
    })

    it('withdraw_when_정지된_계좌_then_에러를_throw한다', () => {
      const account = createActiveAccount(1000)
      account.suspend()

      expect(() => account.withdraw(new Money({ amount: 100, currency: 'KRW' })))
        .toThrow('활성 상태의 계좌만 출금할 수 있습니다.')
    })

    it('withdraw_when_금액이_0이하_then_에러를_throw한다', () => {
      const account = createActiveAccount(1000)

      expect(() => account.withdraw(new Money({ amount: 0, currency: 'KRW' })))
        .toThrow('금액은 0보다 커야 합니다.')
    })

    it('withdraw_when_잔액_초과_금액_then_에러를_throw한다', () => {
      const account = createActiveAccount(100)

      expect(() => account.withdraw(new Money({ amount: 200, currency: 'KRW' })))
        .toThrow('잔액이 부족합니다.')
    })
  })

  describe('applyInterest', () => {
    const today = new Date('2026-07-20T00:00:00.000Z')
    const yesterday = new Date('2026-07-19T00:00:00.000Z')

    it('applyInterest_when_활성_계좌에_이자가_0보다_크면_then_잔액이_증가하고_INTEREST_거래와_InterestPaid_이벤트가_발행된다', () => {
      const account = createActiveAccount(1_000_000)

      const transaction = account.applyInterest(today)

      expect(transaction).not.toBeNull()
      expect(transaction?.type).toBe('INTEREST')
      expect(transaction?.amount.amount).toBe(100)
      expect(account.balance.amount).toBe(1_000_100)
      expect(account.lastInterestPaidAt).toEqual(today)
      expect(account.domainEvents[0].constructor.name).toBe('InterestPaid')
    })

    it('applyInterest_when_계산된_이자가_0이면_then_null을_반환하고_잔액은_변하지_않지만_lastInterestPaidAt은_갱신된다', () => {
      const account = createActiveAccount(100)

      const transaction = account.applyInterest(today)

      expect(transaction).toBeNull()
      expect(account.balance.amount).toBe(100)
      expect(account.lastInterestPaidAt).toEqual(today)
      expect(account.domainEvents).toHaveLength(0)
    })

    it('applyInterest_when_같은_날_두_번_호출되면_then_두_번째_호출은_null을_반환하고_잔액이_중복_증가하지_않는다', () => {
      const account = createActiveAccount(1_000_000)
      account.applyInterest(today)
      account.clearEvents()
      account.clearTransactions()

      const secondCall = account.applyInterest(today)

      expect(secondCall).toBeNull()
      expect(account.balance.amount).toBe(1_000_100)
      expect(account.domainEvents).toHaveLength(0)
    })

    it('applyInterest_when_전날_이미_지급됐고_오늘_다시_호출되면_then_다시_지급된다', () => {
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

    it('applyInterest_when_정지된_계좌면_then_null을_반환하고_lastInterestPaidAt도_변하지_않는다', () => {
      const account = createActiveAccount(1_000_000)
      account.suspend()

      const transaction = account.applyInterest(today)

      expect(transaction).toBeNull()
      expect(account.lastInterestPaidAt).toBeNull()
    })
  })

  describe('suspend', () => {
    it('suspend_when_활성_계좌_then_SUSPENDED_상태가_되고_AccountSuspended_이벤트가_발행된다', () => {
      const account = createActiveAccount()

      account.suspend()

      expect(account.status).toBe(AccountStatus.SUSPENDED)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountSuspended)
    })

    it('suspend_when_이미_정지된_계좌_then_에러를_throw한다', () => {
      const account = createActiveAccount()
      account.suspend()

      expect(() => account.suspend()).toThrow('활성 상태의 계좌만 정지할 수 있습니다.')
    })
  })

  describe('reactivate', () => {
    it('reactivate_when_정지된_계좌_then_ACTIVE_상태가_되고_AccountReactivated_이벤트가_발행된다', () => {
      const account = createActiveAccount()
      account.suspend()
      account.clearEvents()

      account.reactivate()

      expect(account.status).toBe(AccountStatus.ACTIVE)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountReactivated)
    })

    it('reactivate_when_활성_계좌_then_에러를_throw한다', () => {
      const account = createActiveAccount()

      expect(() => account.reactivate()).toThrow('정지 상태의 계좌만 재개할 수 있습니다.')
    })
  })

  describe('close', () => {
    it('close_when_잔액이_0인_계좌_then_CLOSED_상태가_되고_AccountClosed_이벤트가_발행된다', () => {
      const account = createActiveAccount(0)

      account.close()

      expect(account.status).toBe(AccountStatus.CLOSED)
      expect(account.domainEvents[0]).toBeInstanceOf(AccountClosed)
    })

    it('close_when_잔액이_0이_아님_then_에러를_throw한다', () => {
      const account = createActiveAccount(1000)

      expect(() => account.close()).toThrow('잔액이 0이 아닌 계좌는 종료할 수 없습니다.')
    })

    it('close_when_이미_종료된_계좌_then_에러를_throw한다', () => {
      const account = createActiveAccount(0)
      account.close()

      expect(() => account.close()).toThrow('이미 종료된 계좌입니다.')
    })
  })

  describe('clearEvents/clearTransactions', () => {
    it('clearEvents_when_호출_then_domainEvents가_비워진다', () => {
      const account = Account.create({ ownerId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })
      account.clearEvents()
      expect(account.domainEvents).toHaveLength(0)
    })

    it('clearTransactions_when_호출_then_pendingTransactions가_비워진다', () => {
      const account = createActiveAccount(1000)
      account.deposit(new Money({ amount: 100, currency: 'KRW' }))
      account.clearTransactions()
      expect(account.pendingTransactions).toHaveLength(0)
    })
  })
})
