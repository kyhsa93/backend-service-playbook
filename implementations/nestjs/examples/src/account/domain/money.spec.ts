import { Money } from '@/account/domain/money'

describe('Money', () => {
  it('생성_when_금액이_음수_then_에러를_throw한다', () => {
    expect(() => new Money({ amount: -1, currency: 'KRW' })).toThrow('금액은 0 이상이어야 합니다.')
  })

  it('생성_when_금액이_0이상_then_정상_생성된다', () => {
    const money = new Money({ amount: 0, currency: 'KRW' })
    expect(money.amount).toBe(0)
    expect(money.currency).toBe('KRW')
  })

  it('add_when_같은_통화_then_금액이_더해진_새_Money를_반환한다', () => {
    const result = new Money({ amount: 1000, currency: 'KRW' }).add(new Money({ amount: 500, currency: 'KRW' }))
    expect(result.amount).toBe(1500)
    expect(result.currency).toBe('KRW')
  })

  it('add_when_다른_통화_then_에러를_throw한다', () => {
    expect(() => new Money({ amount: 1000, currency: 'KRW' }).add(new Money({ amount: 500, currency: 'USD' })))
      .toThrow('통화가 일치하지 않습니다.')
  })

  it('subtract_when_같은_통화_then_금액이_빠진_새_Money를_반환한다', () => {
    const result = new Money({ amount: 1000, currency: 'KRW' }).subtract(new Money({ amount: 300, currency: 'KRW' }))
    expect(result.amount).toBe(700)
  })

  it('subtract_when_다른_통화_then_에러를_throw한다', () => {
    expect(() => new Money({ amount: 1000, currency: 'KRW' }).subtract(new Money({ amount: 300, currency: 'USD' })))
      .toThrow('통화가 일치하지 않습니다.')
  })

  it('isLessThan_when_금액이_더_작음_then_true를_반환한다', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).isLessThan(new Money({ amount: 200, currency: 'KRW' })))
      .toBe(true)
  })

  it('isLessThan_when_금액이_더_크거나_같음_then_false를_반환한다', () => {
    expect(new Money({ amount: 200, currency: 'KRW' }).isLessThan(new Money({ amount: 200, currency: 'KRW' })))
      .toBe(false)
  })

  it('isZero_when_금액이_0_then_true를_반환한다', () => {
    expect(new Money({ amount: 0, currency: 'KRW' }).isZero()).toBe(true)
  })

  it('isZero_when_금액이_0이_아님_then_false를_반환한다', () => {
    expect(new Money({ amount: 1, currency: 'KRW' }).isZero()).toBe(false)
  })

  it('equals_when_금액과_통화가_같음_then_true를_반환한다', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).equals(new Money({ amount: 100, currency: 'KRW' }))).toBe(true)
  })

  it('equals_when_금액이_다름_then_false를_반환한다', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).equals(new Money({ amount: 200, currency: 'KRW' }))).toBe(false)
  })
})
