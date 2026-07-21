import { computePreviousStatementMonth } from '@/payment/infrastructure/previous-statement-month'

describe('computePreviousStatementMonth', () => {
  it('compute_when_7월_1일_실행되면_then_6월_전체_구간을_반환한다', () => {
    const result = computePreviousStatementMonth(new Date('2026-07-01T01:00:00.000Z'))

    expect(result.statementMonth).toBe('2026-06')
    expect(result.monthStart).toEqual(new Date('2026-06-01T00:00:00.000Z'))
    expect(result.monthEnd).toEqual(new Date('2026-07-01T00:00:00.000Z'))
  })

  it('compute_when_1월_1일_실행되면_then_연도가_바뀐_전년_12월_구간을_반환한다', () => {
    const result = computePreviousStatementMonth(new Date('2026-01-01T01:00:00.000Z'))

    expect(result.statementMonth).toBe('2025-12')
    expect(result.monthStart).toEqual(new Date('2025-12-01T00:00:00.000Z'))
    expect(result.monthEnd).toEqual(new Date('2026-01-01T00:00:00.000Z'))
  })
})
