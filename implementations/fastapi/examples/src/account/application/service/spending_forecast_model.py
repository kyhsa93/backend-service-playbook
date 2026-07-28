from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

from ...domain.spending_forecast import ForecastConfidence


# One month's worth of the training signal — reuses the spending_analysis read-model the
# account.analyze-monthly-spending ETL already produces, rather than re-aggregating raw
# Transaction rows.
@dataclass(frozen=True)
class SpendingHistoryPoint:
    analysis_month: str
    total_amount: int


@dataclass(frozen=True)
class SpendingForecastPrediction:
    predicted_amount: int
    confidence: ForecastConfidence


# A Technical Service (see root docs/architecture/domain-service.md) — the core of this
# feature is a statistical model, an implementation concern independent of any domain rule, so
# it's abstracted the same way NlTransactionQueryTranslator abstracts an LLM call. The
# interface takes/returns plain data only, never a SQLAlchemy model or an account/domain type,
# so the implementation (currently an in-process regression) could later be swapped for a call
# to an external ML service without touching any caller.
class SpendingForecastModel(ABC):
    # history must be in chronological (oldest-first) order. Callers are expected to enforce a
    # minimum history length before calling this — see MIN_HISTORY_MONTHS_FOR_FORECAST in
    # forecast_spending_handler.py. No I/O happens here (a pure in-process computation), so
    # this stays a plain (non-async) method, unlike NlTransactionQueryTranslator.translate.
    @abstractmethod
    def predict(self, history: list[SpendingHistoryPoint]) -> SpendingForecastPrediction: ...
