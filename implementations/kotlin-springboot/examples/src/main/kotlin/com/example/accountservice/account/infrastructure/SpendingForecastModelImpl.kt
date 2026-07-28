package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.service.SpendingForecastModel
import com.example.accountservice.account.application.service.SpendingForecastPrediction
import com.example.accountservice.account.application.service.SpendingHistoryPoint
import com.example.accountservice.account.domain.ForecastConfidence
import org.springframework.stereotype.Component
import kotlin.math.roundToLong

/**
 * Ordinary least squares over (monthIndex, totalAmount) — a genuine trained model (its two
 * parameters, slope and intercept, are fit fresh from each account's own history every time the
 * scheduled job runs) rather than a hardcoded rule, while staying dependency-free and explainable.
 * x is just the position of the month within the trailing history (0, 1, 2, ...), not a calendar
 * value, so a gap month (an account with no analysis for some month) doesn't skew the fit —
 * callers pass in only the months that actually exist.
 */
@Component
class SpendingForecastModelImpl : SpendingForecastModel {
    override fun predict(history: List<SpendingHistoryPoint>): SpendingForecastPrediction {
        val n = history.size
        val xs = DoubleArray(n) { it.toDouble() }
        val ys = DoubleArray(n) { history[it].totalAmount.toDouble() }

        val xMean = xs.average()
        val yMean = ys.average()

        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until n) {
            numerator += (xs[i] - xMean) * (ys[i] - yMean)
            denominator += (xs[i] - xMean) * (xs[i] - xMean)
        }
        // denominator is 0 only when n == 1, which MIN_HISTORY_MONTHS_FOR_FORECAST (>= 3) already
        // rules out for every real caller — guarded here anyway so this stays correct in isolation.
        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        val intercept = yMean - slope * xMean

        val nextMonthIndex = n
        val predictedAmount = maxOf(0L, (intercept + slope * nextMonthIndex).roundToLong())

        var ssTotal = 0.0
        var ssResidual = 0.0
        for (i in 0 until n) {
            ssTotal += (ys[i] - yMean) * (ys[i] - yMean)
            val predicted = intercept + slope * xs[i]
            ssResidual += (ys[i] - predicted) * (ys[i] - predicted)
        }
        // A perfectly flat history (ssTotal == 0) is a perfect fit by definition, not an undefined
        // one — 0/0 would otherwise produce NaN.
        val rSquared = if (ssTotal == 0.0) 1.0 else 1 - ssResidual / ssTotal

        val confidence =
            when {
                rSquared >= HIGH_CONFIDENCE_R_SQUARED -> ForecastConfidence.HIGH
                rSquared >= MEDIUM_CONFIDENCE_R_SQUARED -> ForecastConfidence.MEDIUM
                else -> ForecastConfidence.LOW
            }

        return SpendingForecastPrediction(predictedAmount, confidence)
    }

    companion object {
        private const val HIGH_CONFIDENCE_R_SQUARED = 0.7
        private const val MEDIUM_CONFIDENCE_R_SQUARED = 0.3
    }
}
