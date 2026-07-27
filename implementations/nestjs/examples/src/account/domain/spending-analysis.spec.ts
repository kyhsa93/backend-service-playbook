import { SpendingAnalysis } from '@/account/domain/spending-analysis'

describe('SpendingAnalysis.create', () => {
  it('create_when_spending_increased_by_more_than_10_percent_then_trend_is_INCREASING', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 15000, transactionCount: 3, previousTotalAmount: 10000
    })

    expect(analysis.changeFromPreviousMonth).toBe(50)
    expect(analysis.trend).toBe('INCREASING')
    expect(analysis.averageAmount).toBe(5000)
  })

  it('create_when_spending_decreased_by_more_than_10_percent_then_trend_is_DECREASING', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 5000, transactionCount: 1, previousTotalAmount: 10000
    })

    expect(analysis.changeFromPreviousMonth).toBe(-50)
    expect(analysis.trend).toBe('DECREASING')
  })

  it('create_when_the_change_is_within_10_percent_then_trend_is_STABLE', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 10500, transactionCount: 2, previousTotalAmount: 10000
    })

    expect(analysis.changeFromPreviousMonth).toBe(5)
    expect(analysis.trend).toBe('STABLE')
  })

  it('create_when_there_was_no_spending_in_either_month_then_0_percent_change_and_STABLE', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 0, transactionCount: 0, previousTotalAmount: 0
    })

    expect(analysis.changeFromPreviousMonth).toBe(0)
    expect(analysis.trend).toBe('STABLE')
    expect(analysis.averageAmount).toBe(0)
  })

  it('create_when_there_was_no_spending_last_month_but_spending_this_month_then_100_percent_change_and_INCREASING', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 3000, transactionCount: 1, previousTotalAmount: 0
    })

    expect(analysis.changeFromPreviousMonth).toBe(100)
    expect(analysis.trend).toBe('INCREASING')
  })

  it('create_when_transactionCount_is_0_then_averageAmount_is_0_rather_than_dividing_by_zero', () => {
    const analysis = SpendingAnalysis.create({
      accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 0, transactionCount: 0, previousTotalAmount: 5000
    })

    expect(analysis.averageAmount).toBe(0)
  })
})
