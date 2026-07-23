from fastapi import FastAPI
from pydantic import BaseModel

from model import RefundRiskFeatures, to_vector, train_model

app = FastAPI(title="Fraud Risk Scorer")

# Trained once at process startup against the synthetic dataset in model.py. A real deployment
# would retrain periodically against actual refund history (e.g. a scheduled /train call or a
# batch job) instead of training once from a fixed synthetic set at startup.
_model = train_model()


class ScoreRequest(BaseModel):
    refundCountLast30Days: float
    rejectedRefundCountLast30Days: float
    refundToPaymentAmountRatio: float
    minutesSincePayment: float


class ScoreResponse(BaseModel):
    riskScore: float


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/score", response_model=ScoreResponse)
def score(request: ScoreRequest) -> ScoreResponse:
    features = RefundRiskFeatures(
        refund_count_last_30_days=request.refundCountLast30Days,
        rejected_refund_count_last_30_days=request.rejectedRefundCountLast30Days,
        refund_to_payment_amount_ratio=request.refundToPaymentAmountRatio,
        minutes_since_payment=request.minutesSincePayment,
    )
    risk_score = float(_model.predict_proba([to_vector(features)])[0][1])
    return ScoreResponse(riskScore=risk_score)
