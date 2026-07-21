import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { TransferEligibilityService } from '@/account/domain/transfer-eligibility-service'

// 이 판단(동일 계좌 여부 + 두 계좌 활성 상태 + 통화 일치 + 잔액 충분)은 어느 한쪽
// Account 인스턴스만으로는 검증할 수 없다 — 두 Aggregate 인스턴스를 함께 로드해야만
// 검증 가능한 규칙이라는 것이 TransferEligibilityService가 Domain Service인 이유다.
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

  it('evaluate_when_모든_조건을_만족하면_then_승인한다', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(true)
  })

  it('evaluate_when_출금_계좌와_입금_계좌가_같으면_then_거부한다', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-1', amount: 10000 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('출금 계좌와 입금 계좌가 동일할 수 없습니다.')
  })

  it('evaluate_when_출금_계좌가_비활성이면_then_거부한다', () => {
    const source = createAccount({ accountId: 'account-1', status: AccountStatus.SUSPENDED, amount: 10000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('활성 상태의 계좌만 출금할 수 있습니다.')
  })

  it('evaluate_when_입금_계좌가_비활성이면_then_거부한다', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000 })
    const target = createAccount({ accountId: 'account-2', status: AccountStatus.CLOSED, amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('활성 상태의 계좌만 입금할 수 있습니다.')
  })

  it('evaluate_when_통화가_일치하지_않으면_then_거부한다', () => {
    const source = createAccount({ accountId: 'account-1', amount: 10000, currency: 'KRW' })
    const target = createAccount({ accountId: 'account-2', amount: 0, currency: 'USD' })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('통화가 일치하지 않습니다.')
  })

  it('evaluate_when_출금_계좌_잔액이_부족하면_then_거부한다', () => {
    const source = createAccount({ accountId: 'account-1', amount: 1000 })
    const target = createAccount({ accountId: 'account-2', amount: 0 })

    const decision = service.evaluate(source, target, new Money({ amount: 5000, currency: 'KRW' }))

    expect(decision.approved).toBe(false)
    expect(decision.reason).toBe('잔액이 부족합니다.')
  })
})
