// A pure function computing account.analyze-monthly-spending's target period — "the previous
// month," plus the month before that (for the %-change comparison). The Scheduler carries this
// function's result through the Task payload as-is, the same reason as
// payment/infrastructure/previous-statement-month.ts: recomputing "which month" from the
// clock at processing time (rather than at enqueue time) could close out the wrong month if
// processing is delayed by a queue backlog.
//
// Returns "the entire previous month" (before this month's 1st) and the month before that, both
// by the UTC calendar.
export function computePreviousSpendingAnalysisPeriod(now: Date): {
  analysisMonth: string
  monthStart: Date
  monthEnd: Date
  previousMonthStart: Date
  previousMonthEnd: Date
} {
  const monthStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  const monthEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
  const previousMonthStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 2, 1))
  const previousMonthEnd = monthStart
  const analysisMonth = `${monthStart.getUTCFullYear()}-${String(monthStart.getUTCMonth() + 1).padStart(2, '0')}`
  return { analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd }
}
