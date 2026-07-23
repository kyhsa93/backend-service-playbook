from __future__ import annotations

import os
from typing import Literal

FraudScorerMode = Literal["native", "http"]

DEFAULT_FRAUD_SCORER_MODE: FraudScorerMode = "native"
DEFAULT_FRAUD_SCORER_BASE_URL = "http://localhost:8000"


# 'native' (in-process scikit-learn LogisticRegression — see
# infrastructure/refund_fraud_risk_scorer_native_impl.py) needs no extra service and is the
# default so the app runs standalone. 'http' calls the shared services/fraud-risk-scorer
# microservice (see infrastructure/refund_fraud_risk_scorer_http_impl.py) — opt in via
# FRAUD_SCORER_MODE=http once that service is running (docker-compose.yml's fraud-risk-scorer
# service). Both implementations satisfy the same RefundFraudRiskScorer interface, so switching
# is a one-line env change — the same point RefundReasonClassifier's Claude-API-to-Ollama swap
# demonstrated once already. All os.environ/os.getenv access must live in src/config/*_config.py
# (see config.md) — that's why this plain function form is used rather than pydantic-settings'
# BaseSettings (which would coerce an invalid value instead of falling back to the default).
def get_fraud_scorer_mode() -> FraudScorerMode:
    return "http" if os.getenv("FRAUD_SCORER_MODE") == "http" else DEFAULT_FRAUD_SCORER_MODE


def get_fraud_scorer_base_url() -> str:
    return os.getenv("FRAUD_SCORER_BASE_URL", DEFAULT_FRAUD_SCORER_BASE_URL)
