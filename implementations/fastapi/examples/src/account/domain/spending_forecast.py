from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from ...common.clock import utc_now
from ...common.generate_id import generate_id

# Must live in the domain layer (not application) even though it's only ever produced/consumed
# by application-layer code (SpendingForecastModel, GetSpendingForecastHandler) — the domain
# layer can never import from the application layer, even for a plain type, so the type has to
# live at or below domain/ for both layers to reference it (domain-layer-isolation).
ForecastConfidence = Literal["LOW", "MEDIUM", "HIGH"]


# A materialized read-model row — the ETL's precomputed answer to "what will this account
# likely spend next month," produced monthly by ForecastSpendingHandler (which trains
# SpendingForecastModel fresh from the account's spending_analysis history on every run) and
# served as-is by GetSpendingForecastHandler. No business invariant lives here — the one real
# transform step (fitting the model) already happened before this object is constructed — so
# this stays a plain data holder (a frozen dataclass), the same reasoning as SpendingAnalysis.
@dataclass(frozen=True)
class SpendingForecast:
    forecast_id: str
    account_id: str
    forecast_month: str
    predicted_amount: int
    confidence: ForecastConfidence
    history_months_used: int
    created_at: datetime

    @classmethod
    def create(
        cls,
        account_id: str,
        forecast_month: str,
        predicted_amount: int,
        confidence: ForecastConfidence,
        history_months_used: int,
    ) -> SpendingForecast:
        return cls(
            forecast_id=generate_id(),
            account_id=account_id,
            forecast_month=forecast_month,
            predicted_amount=predicted_amount,
            confidence=confidence,
            history_months_used=history_months_used,
            created_at=utc_now(),
        )
