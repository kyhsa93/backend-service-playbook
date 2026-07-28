// A pure function computing account.forecast-spending's target period — "the current month"
// (the one that just started, when this runs on the 1st at 03:00 UTC, an hour after the
// spending-analysis job at 02:00 has finished writing last month's row). The Scheduler carries
// this function's result through the Task payload as-is, the same reason as
// previous-spending-analysis-period.ts: recomputing "which month" from the clock at processing
// time (rather than at enqueue time) could target the wrong month if processing is delayed by a
// queue backlog.
export function computeSpendingForecastMonth(now: Date): string {
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`
}
