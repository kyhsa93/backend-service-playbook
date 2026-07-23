from __future__ import annotations

import random

import numpy as np
from sklearn.linear_model import LogisticRegression

from ..application.service.refund_fraud_risk_scorer import RefundFraudRiskScorer
from ..domain.refund_risk_features import RefundRiskFeatures

TRAINING_EXAMPLE_COUNT = 300
TRAINING_SEED = 42


# Scales each raw feature into a roughly 0-1 range before it reaches the model — the same
# scaling every other language's native RefundFraudRiskScorer implementation (and the shared
# services/fraud-risk-scorer/model.py microservice) applies, so a given feature vector scores
# comparably regardless of which RefundFraudRiskScorer implementation handles it.
def _to_vector(features: RefundRiskFeatures) -> list[float]:
    return [
        features.refund_count_last_30_days / 10,
        features.rejected_refund_count_last_30_days / 5,
        features.refund_to_payment_amount_ratio,
        min(1.0, features.minutes_since_payment / 1440),
    ]


# A synthetic seed dataset standing in for real historical fraud-review outcomes — this
# example has no real user base to draw labeled data from. The label follows an explicit
# ground-truth rule (frequent + high-ratio + fast-after-payment refunds are risky) purely so
# the model has a non-trivial pattern to fit; replace this with real labeled history in
# production. This exact generation rule (TRAINING_EXAMPLE_COUNT=300, TRAINING_SEED=42, the
# same risk_score formula) is mirrored in services/fraud-risk-scorer/model.py and every other
# language's native implementation, so both sides of the "shared service vs. native" pair — and
# every language's own native model — are trained on equivalent data. Deliberately
# self-contained (no import from the repo-root services/ package) so this app has no runtime
# dependency on that directory; only refund_fraud_risk_scorer_http_impl.py calls it, over the
# network.
def _generate_training_data() -> tuple[np.ndarray, np.ndarray]:
    rng = random.Random(TRAINING_SEED)
    vectors: list[list[float]] = []
    labels: list[int] = []

    for _ in range(TRAINING_EXAMPLE_COUNT):
        refund_count = rng.randint(0, 7)
        rejected_count = rng.randint(0, 3)
        ratio = rng.random()
        minutes = rng.random() * 43200
        risk_score = refund_count * 0.15 + rejected_count * 0.3 + ratio * 0.4 + max(0.0, 1 - minutes / 1440) * 0.3
        label = 1 if risk_score > 1.1 else 0
        features = RefundRiskFeatures(
            refund_count_last_30_days=refund_count,
            rejected_refund_count_last_30_days=rejected_count,
            refund_to_payment_amount_ratio=ratio,
            minutes_since_payment=minutes,
        )
        vectors.append(_to_vector(features))
        labels.append(label)

    return np.array(vectors), np.array(labels)


def _train_model() -> LogisticRegression:
    x, y = _generate_training_data()
    model = LogisticRegression()
    model.fit(x, y)
    return model


# Trained once at import time (module import is a one-time event per process) against the
# synthetic dataset above — a module-level global, the same singleton-caching pattern this
# repo's src/database.py (`engine`, `SessionLocal`) already uses in place of a DI container's
# SINGLETON scope (see module-pattern.md). A real deployment would retrain periodically against
# actual refund history instead of training once from a fixed synthetic set at startup.
_model = _train_model()


# A Technical Service (see root docs/architecture/domain-service.md) using scikit-learn
# directly — since this IS a Python implementation, there's no reason to hand-roll gradient
# descent the way a language with no first-class ML library in this repo's dependency set
# would. Self-contained; no extra service needed. See refund_fraud_risk_scorer_http_impl.py for
# the other half of the pair (the shared microservice, called over HTTP).
class RefundFraudRiskScorerNativeImpl(RefundFraudRiskScorer):
    async def score(self, features: RefundRiskFeatures) -> float:
        vector = _to_vector(features)
        return float(_model.predict_proba([vector])[0][1])
