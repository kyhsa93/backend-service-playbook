// A pure function computing payment.send-card-statements's target period ("the previous
// month"). The Scheduler carries this function's result (statementMonth/monthStart/monthEnd)
// through as-is into the Task payload — because if the Consumer recomputed it from the actual
// clock at processing time, it could close out the wrong month when processing is delayed by
// an SQS backlog (e.g. processed after the month has changed) (the same reason as
// account.apply-daily-interest's today payload).
//
// Returns "the entire previous month" (before this month's 1st, on/after last month's 1st) by the UTC calendar.
export function computePreviousStatementMonth(now: Date): {
  statementMonth: string
  monthStart: Date
  monthEnd: Date
} {
  const monthStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  const monthEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
  const statementMonth = `${monthStart.getUTCFullYear()}-${String(monthStart.getUTCMonth() + 1).padStart(2, '0')}`
  return { statementMonth, monthStart, monthEnd }
}
