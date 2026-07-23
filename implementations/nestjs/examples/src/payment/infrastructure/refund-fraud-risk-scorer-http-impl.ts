import { Injectable, Logger } from '@nestjs/common'

import { getFraudScorerBaseUrl } from '@/config/fraud-risk.config'
import { RefundFraudRiskScorer } from '@/payment/application/service/refund-fraud-risk-scorer'
import { RefundRiskFeatures } from '@/payment/domain/refund-risk-features'

// Used whenever the score can't be trusted (the shared scorer unreachable, malformed output).
// A neutral 0 never blocks the refund flow on its own — RefundEligibilityService's other
// checks still run against it, the same fallback stance as RefundReasonClassifierImpl's.
const FALLBACK_SCORE = 0

interface ScoreResponse {
  riskScore?: number
}

// A Technical Service wrapping the shared services/fraud-risk-scorer microservice (Python +
// scikit-learn, trained on the same synthetic dataset as
// refund-fraud-risk-scorer-native-impl.ts's hand-rolled model). Every one of the 5 language
// implementations calls this same service over plain HTTP — the "one shared model" side of the
// pair; see config/fraud-risk.config.ts for how FRAUD_SCORER_MODE selects this impl over the
// in-process native one.
@Injectable()
export class RefundFraudRiskScorerHttpImpl extends RefundFraudRiskScorer {
  private readonly logger = new Logger(RefundFraudRiskScorerHttpImpl.name)

  public async score(features: RefundRiskFeatures): Promise<number> {
    try {
      const response = await fetch(`${getFraudScorerBaseUrl()}/score`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(features)
      })

      if (!response.ok) {
        this.logger.warn({ message: 'Fraud risk scoring failed, using fallback', status: response.status })
        return FALLBACK_SCORE
      }

      const body = await response.json() as ScoreResponse
      if (typeof body.riskScore !== 'number') return FALLBACK_SCORE

      return Math.min(1, Math.max(0, body.riskScore))
    } catch (error) {
      // A scoring failure is a technical-infrastructure concern, not a domain error — it must
      // never block a refund request. Swallow it here at the boundary and fall back.
      this.logger.warn({ message: 'Fraud risk scoring failed, using fallback', error: (error as Error).message })
      return FALLBACK_SCORE
    }
  }
}
