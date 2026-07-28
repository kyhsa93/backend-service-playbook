import { Injectable } from '@nestjs/common'

import {
  SpendingForecastModel,
  SpendingForecastPrediction,
  SpendingHistoryPoint
} from '@/account/application/service/spending-forecast-model'
import { ForecastConfidence } from '@/account/domain/spending-forecast'

const HIGH_CONFIDENCE_R_SQUARED = 0.7
const MEDIUM_CONFIDENCE_R_SQUARED = 0.3

// Ordinary least squares over (monthIndex, totalAmount) — a genuine trained model (its two
// parameters, slope and intercept, are fit fresh from each account's own history every time
// the scheduled job runs) rather than a hardcoded rule, while staying dependency-free and
// explainable. x is just the position of the month within the trailing history (0, 1, 2, ...),
// not a calendar value, so a gap month (an account with no analysis for some month) doesn't
// skew the fit — callers pass in only the months that actually exist.
@Injectable()
export class SpendingForecastModelImpl extends SpendingForecastModel {
  public predict(history: SpendingHistoryPoint[]): SpendingForecastPrediction {
    const n = history.length
    const xs = history.map((_, i) => i)
    const ys = history.map((point) => point.totalAmount)

    const xMean = xs.reduce((sum, x) => sum + x, 0) / n
    const yMean = ys.reduce((sum, y) => sum + y, 0) / n

    let numerator = 0
    let denominator = 0
    for (let i = 0; i < n; i++) {
      numerator += (xs[i] - xMean) * (ys[i] - yMean)
      denominator += (xs[i] - xMean) ** 2
    }
    // denominator is 0 only when n === 1, which MIN_HISTORY_MONTHS_FOR_FORECAST (>= 3) already
    // rules out for every real caller — guarded here anyway so this stays correct in isolation.
    const slope = denominator === 0 ? 0 : numerator / denominator
    const intercept = yMean - slope * xMean

    const nextMonthIndex = n
    const predictedAmount = Math.max(0, Math.round(intercept + slope * nextMonthIndex))

    const ssTotal = ys.reduce((sum, y) => sum + (y - yMean) ** 2, 0)
    const ssResidual = ys.reduce((sum, y, i) => sum + (y - (intercept + slope * xs[i])) ** 2, 0)
    // A perfectly flat history (ssTotal === 0) is a perfect fit by definition, not an
    // undefined one — 0/0 would otherwise produce NaN.
    const rSquared = ssTotal === 0 ? 1 : 1 - ssResidual / ssTotal

    let confidence: ForecastConfidence = 'LOW'
    if (rSquared >= HIGH_CONFIDENCE_R_SQUARED) confidence = 'HIGH'
    else if (rSquared >= MEDIUM_CONFIDENCE_R_SQUARED) confidence = 'MEDIUM'

    return { predictedAmount, confidence }
  }
}
