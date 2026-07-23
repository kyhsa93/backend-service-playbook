import { Injectable } from '@nestjs/common'

import { RefundFraudRiskScorer } from '@/payment/application/service/refund-fraud-risk-scorer'
import { RefundRiskFeatures } from '@/payment/domain/refund-risk-features'

interface TrainingExample {
  readonly features: RefundRiskFeatures
  readonly label: number
}

// Fixed-increment LCG (no external dependency) so the generated dataset — and therefore the
// trained weights — is identical on every run.
function seededRandom(seed: number): () => number {
  let state = seed
  return (): number => {
    state = (state * 1103515245 + 12345) & 0x7fffffff
    return state / 0x7fffffff
  }
}

// A synthetic seed dataset standing in for real historical fraud-review outcomes — this
// example has no real user base to draw labeled data from. The label follows an explicit
// ground-truth rule (frequent + high-ratio + fast-after-payment refunds are risky) purely so
// the model has a non-trivial pattern to fit; replace this with real labeled history in
// production. This same generation rule is mirrored in services/fraud-risk-scorer/model.py so
// both implementations of RefundFraudRiskScorer are trained on equivalent data.
function generateTrainingData(): TrainingExample[] {
  const random = seededRandom(42)
  const examples: TrainingExample[] = []
  for (let i = 0; i < 300; i++) {
    const refundCountLast30Days = Math.floor(random() * 8)
    const rejectedRefundCountLast30Days = Math.floor(random() * 4)
    const refundToPaymentAmountRatio = random()
    const minutesSincePayment = random() * 43200
    const riskScore = refundCountLast30Days * 0.15
      + rejectedRefundCountLast30Days * 0.3
      + refundToPaymentAmountRatio * 0.4
      + Math.max(0, 1 - minutesSincePayment / 1440) * 0.3
    const label = riskScore > 1.1 ? 1 : 0
    examples.push({
      features: { refundCountLast30Days, rejectedRefundCountLast30Days, refundToPaymentAmountRatio, minutesSincePayment },
      label
    })
  }
  return examples
}

// Scales each raw feature into a roughly 0-1 range before it reaches the model — plain gradient
// descent converges far more slowly (and less reliably) on unscaled inputs this different in
// magnitude (a single-digit count next to a value in the thousands).
function toVector(features: RefundRiskFeatures): number[] {
  return [
    features.refundCountLast30Days / 10,
    features.rejectedRefundCountLast30Days / 5,
    features.refundToPaymentAmountRatio,
    Math.min(1, features.minutesSincePayment / 1440)
  ]
}

function sigmoid(z: number): number {
  return 1 / (1 + Math.exp(-z))
}

// Batch gradient descent on plain logistic regression — no external ML library, deliberately
// simple/inspectable (see root docs/architecture/domain-service.md's second RefundFraudRiskScorer
// example): this is the "each language trains natively" side of the pair, the shared
// services/fraud-risk-scorer HTTP microservice (refund-fraud-risk-scorer-http-impl.ts) is the
// "one shared service" side.
function trainLogisticRegression(examples: TrainingExample[]): { weights: number[]; bias: number } {
  const learningRate = 0.5
  const epochs = 500
  const featureCount = toVector(examples[0].features).length
  const weights = new Array(featureCount).fill(0)
  let bias = 0

  for (let epoch = 0; epoch < epochs; epoch++) {
    const weightGradients = new Array(featureCount).fill(0)
    let biasGradient = 0

    for (const example of examples) {
      const vector = toVector(example.features)
      const prediction = sigmoid(vector.reduce((sum, x, i) => sum + x * weights[i], bias))
      const error = prediction - example.label
      for (let i = 0; i < featureCount; i++) weightGradients[i] += error * vector[i]
      biasGradient += error
    }

    for (let i = 0; i < featureCount; i++) weights[i] -= (learningRate * weightGradients[i]) / examples.length
    bias -= (learningRate * biasGradient) / examples.length
  }

  return { weights, bias }
}

@Injectable()
export class RefundFraudRiskScorerNativeImpl extends RefundFraudRiskScorer {
  // Trained once, at construction (this provider is a singleton — see payment-module.ts),
  // against the synthetic dataset above. A real deployment would retrain periodically against
  // actual refund history instead of training once from a fixed synthetic set at startup.
  private readonly model = trainLogisticRegression(generateTrainingData())

  public async score(features: RefundRiskFeatures): Promise<number> {
    const vector = toVector(features)
    const z = vector.reduce((sum, x, i) => sum + x * this.model.weights[i], this.model.bias)
    return sigmoid(z)
  }
}
