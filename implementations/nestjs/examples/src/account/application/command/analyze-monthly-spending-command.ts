// The payload of the account.analyze-monthly-spending Task. All four dates are computed by the
// Scheduler at enqueue time and carried through as-is — the same reason as
// account.apply-daily-interest's `today`: if the Consumer recomputed "which month" from the
// actual clock at processing time, a delayed/backlogged run could analyze the wrong month.
export class AnalyzeMonthlySpendingCommand {
  public readonly analysisMonth: string
  public readonly monthStart: Date
  public readonly monthEnd: Date
  public readonly previousMonthStart: Date
  public readonly previousMonthEnd: Date

  constructor(command: {
    analysisMonth: string
    monthStart: Date
    monthEnd: Date
    previousMonthStart: Date
    previousMonthEnd: Date
  }) {
    this.analysisMonth = command.analysisMonth
    this.monthStart = command.monthStart
    this.monthEnd = command.monthEnd
    this.previousMonthStart = command.previousMonthStart
    this.previousMonthEnd = command.previousMonthEnd
  }
}
