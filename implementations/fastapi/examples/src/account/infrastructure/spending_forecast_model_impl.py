from __future__ import annotations

import math

from ..application.service.spending_forecast_model import (
    SpendingForecastModel,
    SpendingForecastPrediction,
    SpendingHistoryPoint,
)
from ..domain.spending_forecast import ForecastConfidence

HIGH_CONFIDENCE_R_SQUARED = 0.7
MEDIUM_CONFIDENCE_R_SQUARED = 0.3


def _round_half_up(value: float) -> int:
    """Python's built-in round() rounds half-to-even (banker's rounding), which diverges from
    JS's Math.round (round half up, i.e. floor(x + 0.5)) exactly at a .5 tie — this repository's
    nestjs reference (SpendingForecastModelImpl) relies on that half-up behavior, so this
    helper reproduces it exactly, the same trick spending_analysis.py's own
    _round_half_up already applies for the same reason."""
    return math.floor(value + 0.5)


# Ordinary least squares over (monthIndex, totalAmount) — a genuine trained model (its two
# parameters, slope and intercept, are fit fresh from each account's own history every time the
# scheduled job runs) rather than a hardcoded rule, while staying dependency-free and
# explainable. x is just the position of the month within the trailing history (0, 1, 2, ...),
# not a calendar value, so a gap month (an account with no analysis for some month) doesn't skew
# the fit — callers pass in only the months that actually exist.
class SpendingForecastModelImpl(SpendingForecastModel):
    def predict(self, history: list[SpendingHistoryPoint]) -> SpendingForecastPrediction:
        n = len(history)
        xs = list(range(n))
        ys = [point.total_amount for point in history]

        x_mean = sum(xs) / n
        y_mean = sum(ys) / n

        numerator = sum((xs[i] - x_mean) * (ys[i] - y_mean) for i in range(n))
        denominator = sum((xs[i] - x_mean) ** 2 for i in range(n))
        # denominator is 0 only when n == 1, which MIN_HISTORY_MONTHS_FOR_FORECAST (>= 3)
        # already rules out for every real caller — guarded here anyway so this stays correct
        # in isolation.
        slope = 0.0 if denominator == 0 else numerator / denominator
        intercept = y_mean - slope * x_mean

        next_month_index = n
        predicted_amount = max(0, _round_half_up(intercept + slope * next_month_index))

        ss_total = sum((y - y_mean) ** 2 for y in ys)
        ss_residual = sum((ys[i] - (intercept + slope * xs[i])) ** 2 for i in range(n))
        # A perfectly flat history (ss_total == 0) is a perfect fit by definition, not an
        # undefined one — 0/0 would otherwise produce a ZeroDivisionError.
        r_squared = 1.0 if ss_total == 0 else 1 - ss_residual / ss_total

        confidence: ForecastConfidence = "LOW"
        if r_squared >= HIGH_CONFIDENCE_R_SQUARED:
            confidence = "HIGH"
        elif r_squared >= MEDIUM_CONFIDENCE_R_SQUARED:
            confidence = "MEDIUM"

        return SpendingForecastPrediction(predicted_amount=predicted_amount, confidence=confidence)
