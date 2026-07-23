# Fraud Risk Scorer

A small shared microservice: trains a logistic regression model (scikit-learn) on a synthetic
refund-history dataset at startup and serves risk scores over HTTP.

This is the "one shared service" side of `RefundFraudRiskScorer` (see
`docs/architecture/domain-service.md`). Every one of the 5 language implementations can call
this same service — via `FRAUD_SCORER_MODE=http` — instead of training a model natively
in-process. Each language's `docker-compose.yml` builds and runs its own instance of this
service under the `ml` profile (the same duplication convention the `ollama` service already
follows — every language stack stays independently runnable on its own).

## Running standalone

```bash
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

## API

- `GET /health` — liveness check.
- `POST /score` — body: `{refundCountLast30Days, rejectedRefundCountLast30Days, refundToPaymentAmountRatio, minutesSincePayment}`, returns `{riskScore}` (0-1).

## Testing

```bash
pip install -r requirements.txt
pytest
```
