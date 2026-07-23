export type FraudScorerMode = 'http' | 'native'

const DEFAULT_FRAUD_SCORER_MODE: FraudScorerMode = 'native'
const DEFAULT_FRAUD_SCORER_BASE_URL = 'http://localhost:8000'

// 'native' (in-process, hand-rolled logistic regression — see
// refund-fraud-risk-scorer-native-impl.ts) needs no extra service and is the default so the
// app runs standalone. 'http' calls the shared services/fraud-risk-scorer microservice (see
// refund-fraud-risk-scorer-http-impl.ts) — opt in via FRAUD_SCORER_MODE=http once that service
// is running (docker-compose.yml's fraud-risk-scorer service). Both implementations satisfy
// the same RefundFraudRiskScorer interface, so switching is a one-line env change — the same
// point RefundReasonClassifier's Claude-API-to-Ollama swap demonstrated once already.
export function getFraudScorerMode(): FraudScorerMode {
  return process.env.FRAUD_SCORER_MODE === 'http' ? 'http' : DEFAULT_FRAUD_SCORER_MODE
}

export function getFraudScorerBaseUrl(): string {
  return process.env.FRAUD_SCORER_BASE_URL ?? DEFAULT_FRAUD_SCORER_BASE_URL
}
