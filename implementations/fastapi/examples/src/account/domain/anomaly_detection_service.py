from __future__ import annotations

import statistics

# The z-score threshold beyond which a withdrawal is flagged — 3 standard deviations from the
# account's own historical mean, the conventional statistical outlier cutoff (~0.3% of a normal
# distribution falls beyond it), tunable if it proves too noisy/quiet in practice.
Z_SCORE_THRESHOLD = 3

# A cold-start guard — with fewer than 5 prior withdrawals, a mean/stddev computed from the
# history is too noisy to judge anything against (the same reasoning as
# MIN_HISTORY_MONTHS_FOR_FORECAST in forecast_spending_handler.py).
MIN_HISTORY_FOR_DETECTION = 5


class AnomalyDetectionService:
    """A Domain Service (see docs/architecture/domain-service.md) — pure logic, no I/O, no
    framework dependency. It is never registered in any DI container such as FastAPI's
    Depends — the Application layer instantiates it directly whenever needed (the same
    reasoning as TransferEligibilityService/RefundEligibilityService).

    The judgment ("is this withdrawal unusual for this account") only makes sense against
    that account's own history, which is more than one Transaction's worth of data (every
    recent withdrawal), so it can't live on Account itself. Trains nothing persisted — like
    SpendingForecastModelImpl, it fits fresh (here: a mean/stddev, not a regression) from
    whatever history the caller passes in, every time it's asked.
    """

    def is_anomalous(self, historical_amounts: list[int], amount: int) -> bool:
        if len(historical_amounts) < MIN_HISTORY_FOR_DETECTION:
            return False

        mean = statistics.mean(historical_amounts)
        std_dev = statistics.pstdev(historical_amounts, mu=mean)

        # A perfectly uniform history (std_dev == 0) has no spread to divide a z-score by —
        # any amount other than that constant is, by definition, the account's first-ever
        # deviation.
        if std_dev == 0:
            return amount != mean

        z_score = abs(amount - mean) / std_dev
        return z_score > Z_SCORE_THRESHOLD
