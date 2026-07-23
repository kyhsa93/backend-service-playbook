from abc import ABC, abstractmethod

from ...domain.refund_risk_features import RefundRiskFeatures


class RefundFraudRiskScorer(ABC):
    """A Technical Service (see root docs/architecture/domain-service.md) abstracting an ML
    fraud-risk model — trained on refund/payment history rather than the free-text reason
    RefundReasonClassifier handles. RefundEligibilityService never depends on this interface
    and never trains or calls a model itself — it only ever receives the already-computed
    score as a plain number and applies its own fixed threshold. Two implementations exist
    side by side (infrastructure/refund_fraud_risk_scorer_native_impl.py,
    infrastructure/refund_fraud_risk_scorer_http_impl.py); which one is wired up is a config
    choice (see config/fraud_risk_config.py), never a Domain concern.
    """

    @abstractmethod
    async def score(self, features: RefundRiskFeatures) -> float: ...
