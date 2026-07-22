import { Money } from '@/account/domain/money'

describe('Money', () => {
  it('constructor_when_amount_is_negative_then_throws', () => {
    expect(() => new Money({ amount: -1, currency: 'KRW' })).toThrow('The amount must be 0 or greater.')
  })

  it('constructor_when_amount_is_0_or_greater_then_creates_successfully', () => {
    const money = new Money({ amount: 0, currency: 'KRW' })
    expect(money.amount).toBe(0)
    expect(money.currency).toBe('KRW')
  })

  it('add_when_same_currency_then_returns_new_Money_with_summed_amount', () => {
    const result = new Money({ amount: 1000, currency: 'KRW' }).add(new Money({ amount: 500, currency: 'KRW' }))
    expect(result.amount).toBe(1500)
    expect(result.currency).toBe('KRW')
  })

  it('add_when_different_currency_then_throws', () => {
    expect(() => new Money({ amount: 1000, currency: 'KRW' }).add(new Money({ amount: 500, currency: 'USD' })))
      .toThrow('The currencies do not match.')
  })

  it('subtract_when_same_currency_then_returns_new_Money_with_subtracted_amount', () => {
    const result = new Money({ amount: 1000, currency: 'KRW' }).subtract(new Money({ amount: 300, currency: 'KRW' }))
    expect(result.amount).toBe(700)
  })

  it('subtract_when_different_currency_then_throws', () => {
    expect(() => new Money({ amount: 1000, currency: 'KRW' }).subtract(new Money({ amount: 300, currency: 'USD' })))
      .toThrow('The currencies do not match.')
  })

  it('isLessThan_when_amount_is_smaller_then_returns_true', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).isLessThan(new Money({ amount: 200, currency: 'KRW' })))
      .toBe(true)
  })

  it('isLessThan_when_amount_is_greater_or_equal_then_returns_false', () => {
    expect(new Money({ amount: 200, currency: 'KRW' }).isLessThan(new Money({ amount: 200, currency: 'KRW' })))
      .toBe(false)
  })

  it('isZero_when_amount_is_0_then_returns_true', () => {
    expect(new Money({ amount: 0, currency: 'KRW' }).isZero()).toBe(true)
  })

  it('isZero_when_amount_is_not_0_then_returns_false', () => {
    expect(new Money({ amount: 1, currency: 'KRW' }).isZero()).toBe(false)
  })

  it('equals_when_amount_and_currency_are_the_same_then_returns_true', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).equals(new Money({ amount: 100, currency: 'KRW' }))).toBe(true)
  })

  it('equals_when_amount_differs_then_returns_false', () => {
    expect(new Money({ amount: 100, currency: 'KRW' }).equals(new Money({ amount: 200, currency: 'KRW' }))).toBe(false)
  })
})
