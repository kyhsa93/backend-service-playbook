import { computePreviousStatementMonth } from '@/payment/infrastructure/previous-statement-month'

describe('computePreviousStatementMonth', () => {
  it('compute_when_run_on_July_1st_then_returns_the_full_June_range', () => {
    const result = computePreviousStatementMonth(new Date('2026-07-01T01:00:00.000Z'))

    expect(result.statementMonth).toBe('2026-06')
    expect(result.monthStart).toEqual(new Date('2026-06-01T00:00:00.000Z'))
    expect(result.monthEnd).toEqual(new Date('2026-07-01T00:00:00.000Z'))
  })

  it('compute_when_run_on_January_1st_then_returns_the_previous_years_December_range', () => {
    const result = computePreviousStatementMonth(new Date('2026-01-01T01:00:00.000Z'))

    expect(result.statementMonth).toBe('2025-12')
    expect(result.monthStart).toEqual(new Date('2025-12-01T00:00:00.000Z'))
    expect(result.monthEnd).toEqual(new Date('2026-01-01T00:00:00.000Z'))
  })
})
