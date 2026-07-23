import random
from dataclasses import dataclass

import numpy as np
from sklearn.linear_model import LogisticRegression

TRAINING_EXAMPLE_COUNT = 300
TRAINING_SEED = 42


@dataclass
class RefundRiskFeatures:
    refund_count_last_30_days: float
    rejected_refund_count_last_30_days: float
    refund_to_payment_amount_ratio: float
    minutes_since_payment: float


# Scales each raw feature into a roughly 0-1 range before it reaches the model — the same
# scaling implementations/nestjs/.../refund-fraud-risk-scorer-native-impl.ts (and every other
# language's native implementation) applies, so a given feature vector scores comparably
# regardless of which RefundFraudRiskScorer implementation handles it.
def to_vector(features: RefundRiskFeatures) -> list[float]:
    return [
        features.refund_count_last_30_days / 10,
        features.rejected_refund_count_last_30_days / 5,
        features.refund_to_payment_amount_ratio,
        min(1.0, features.minutes_since_payment / 1440),
    ]


# A synthetic seed dataset standing in for real historical fraud-review outcomes — this example
# has no real user base to draw labeled data from. The label follows an explicit ground-truth
# rule (frequent + high-ratio + fast-after-payment refunds are risky) purely so the model has a
# non-trivial pattern to fit; replace this with real labeled history in production. This same
# generation rule is mirrored in every language's native RefundFraudRiskScorer implementation so
# both sides of the "shared service vs. native" pair are trained on equivalent data.
def generate_training_data() -> tuple[np.ndarray, np.ndarray]:
    rng = random.Random(TRAINING_SEED)
    vectors: list[list[float]] = []
    labels: list[int] = []

    for _ in range(TRAINING_EXAMPLE_COUNT):
        refund_count = rng.randint(0, 7)
        rejected_count = rng.randint(0, 3)
        ratio = rng.random()
        minutes = rng.random() * 43200
        risk_score = (
            refund_count * 0.15
            + rejected_count * 0.3
            + ratio * 0.4
            + max(0.0, 1 - minutes / 1440) * 0.3
        )
        label = 1 if risk_score > 1.1 else 0
        features = RefundRiskFeatures(refund_count, rejected_count, ratio, minutes)
        vectors.append(to_vector(features))
        labels.append(label)

    return np.array(vectors), np.array(labels)


def train_model() -> LogisticRegression:
    X, y = generate_training_data()
    model = LogisticRegression()
    model.fit(X, y)
    return model
