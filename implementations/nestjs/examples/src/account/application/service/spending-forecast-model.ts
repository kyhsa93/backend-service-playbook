import { ForecastConfidence } from '@/account/domain/spending-forecast'

// One month's worth of the training signal — reuses the spending_analysis read-model the
// account.analyze-monthly-spending ETL already produces, rather than re-aggregating raw
// Transaction rows.
export interface SpendingHistoryPoint {
  readonly analysisMonth: string
  readonly totalAmount: number
}

export interface SpendingForecastPrediction {
  readonly predictedAmount: number
  readonly confidence: ForecastConfidence
}

// A Technical Service (see root docs/architecture/domain-service.md) — the core of this
// feature is a statistical model, an implementation concern independent of any domain rule,
// so it's abstracted the same way NlTransactionQueryTranslator abstracts an LLM call. The
// interface takes/returns plain data only, never a TypeORM entity or an account/domain type,
// so the implementation (currently an in-process regression) could later be swapped for a
// call to an external ML service without touching any caller.
export abstract class SpendingForecastModel {
  // history must be in chronological (oldest-first) order. Callers are expected to enforce a
  // minimum history length before calling this — see MIN_HISTORY_MONTHS_FOR_FORECAST in
  // forecast-spending-command-handler.ts.
  abstract predict(history: SpendingHistoryPoint[]): SpendingForecastPrediction
}
