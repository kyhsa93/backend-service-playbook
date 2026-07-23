from __future__ import annotations

import logging

import httpx

from ...config.fraud_risk_config import get_fraud_scorer_base_url
from ..application.service.refund_fraud_risk_scorer import RefundFraudRiskScorer
from ..domain.refund_risk_features import RefundRiskFeatures

logger = logging.getLogger(__name__)

# Used whenever the score can't be trusted (the shared scorer unreachable, malformed output). A
# neutral 0 never blocks the refund flow on its own — RefundEligibilityService's other checks
# still run against it, the same fallback stance as RefundReasonClassifierImpl's.
FALLBACK_SCORE = 0.0


# A Technical Service wrapping the shared services/fraud-risk-scorer microservice (Python +
# scikit-learn, trained on the same synthetic dataset as
# refund_fraud_risk_scorer_native_impl.py's model). Every one of the 5 language implementations
# calls this same service over plain HTTP — the "one shared model" side of the pair; see
# config/fraud_risk_config.py for how FRAUD_SCORER_MODE selects this impl over the in-process
# native one.
class RefundFraudRiskScorerHttpImpl(RefundFraudRiskScorer):
    async def score(self, features: RefundRiskFeatures) -> float:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{get_fraud_scorer_base_url()}/score",
                    json={
                        "refundCountLast30Days": features.refund_count_last_30_days,
                        "rejectedRefundCountLast30Days": features.rejected_refund_count_last_30_days,
                        "refundToPaymentAmountRatio": features.refund_to_payment_amount_ratio,
                        "minutesSincePayment": features.minutes_since_payment,
                    },
                )

            if response.status_code != 200:
                logger.warning("Fraud risk scoring failed, using fallback: status=%s", response.status_code)
                return FALLBACK_SCORE

            body = response.json()
            risk_score = body.get("riskScore")
            if not isinstance(risk_score, int | float):
                return FALLBACK_SCORE

            return min(1.0, max(0.0, float(risk_score)))
        except Exception:  # noqa: BLE001 - a scoring failure must never block a refund request
            # A scoring failure is a technical-infrastructure concern, not a domain error — it
            # must never block a refund request. Swallow it here at the boundary and fall back.
            logger.warning("Fraud risk scoring failed, using fallback", exc_info=True)
            return FALLBACK_SCORE
