package com.example.accountservice.payment.infrastructure

import com.example.accountservice.payment.application.service.RefundFraudRiskScorer
import com.example.accountservice.payment.domain.RefundRiskFeatures
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.random.Random

private const val TRAINING_EXAMPLE_COUNT = 300
private const val TRAINING_SEED = 42
private const val LEARNING_RATE = 0.5
private const val EPOCHS = 500
private const val FEATURE_COUNT = 4

private data class TrainingExample(
    val features: RefundRiskFeatures,
    val label: Double,
)

/**
 * A synthetic seed dataset standing in for real historical fraud-review outcomes — this example has
 * no real user base to draw labeled data from. The label follows an explicit ground-truth rule
 * (frequent + high-ratio + fast-after-payment refunds are risky) purely so the model has a
 * non-trivial pattern to fit; replace this with real labeled history in production. This same
 * generation rule is mirrored in `services/fraud-risk-scorer/model.py` so both implementations of
 * [RefundFraudRiskScorer] are trained on equivalent data. [Random(TRAINING_SEED)] (a fixed seed) is
 * used so the generated dataset — and therefore the trained weights — is identical on every run.
 */
private fun generateTrainingData(): List<TrainingExample> {
    val random = Random(TRAINING_SEED)
    return (0 until TRAINING_EXAMPLE_COUNT).map {
        val refundCountLast30Days = random.nextInt(8)
        val rejectedRefundCountLast30Days = random.nextInt(4)
        val refundToPaymentAmountRatio = random.nextDouble()
        val minutesSincePayment = random.nextDouble() * 43200
        val riskScore =
            refundCountLast30Days * 0.15 +
                rejectedRefundCountLast30Days * 0.3 +
                refundToPaymentAmountRatio * 0.4 +
                maxOf(0.0, 1 - minutesSincePayment / 1440) * 0.3
        val label = if (riskScore > 1.1) 1.0 else 0.0
        TrainingExample(
            features =
                RefundRiskFeatures(
                    refundCountLast30Days = refundCountLast30Days,
                    rejectedRefundCountLast30Days = rejectedRefundCountLast30Days,
                    refundToPaymentAmountRatio = refundToPaymentAmountRatio,
                    minutesSincePayment = minutesSincePayment,
                ),
            label = label,
        )
    }
}

/**
 * Scales each raw feature into a roughly 0-1 range before it reaches the model — plain gradient
 * descent converges far more slowly (and less reliably) on unscaled inputs this different in
 * magnitude (a single-digit count next to a value in the thousands). Mirrored exactly in
 * `services/fraud-risk-scorer/model.py`'s `to_vector`.
 */
private fun toVector(features: RefundRiskFeatures): DoubleArray =
    doubleArrayOf(
        features.refundCountLast30Days / 10.0,
        features.rejectedRefundCountLast30Days / 5.0,
        features.refundToPaymentAmountRatio,
        minOf(1.0, features.minutesSincePayment / 1440),
    )

private fun sigmoid(z: Double): Double = 1 / (1 + Math.exp(-z))

private data class LogisticModel(
    val weights: DoubleArray,
    val bias: Double,
)

/**
 * Batch gradient descent on plain logistic regression — no external ML library, deliberately
 * simple/inspectable (see root `docs/architecture/domain-service.md`'s `RefundFraudRiskScorer`
 * example): this is the "each language trains natively" side of the pair, the shared
 * `services/fraud-risk-scorer` HTTP microservice ([RefundFraudRiskScorerHttpImpl]) is the "one shared
 * service" side.
 */
private fun trainLogisticRegression(examples: List<TrainingExample>): LogisticModel {
    val weights = DoubleArray(FEATURE_COUNT)
    var bias = 0.0
    val n = examples.size.toDouble()

    repeat(EPOCHS) {
        val weightGradients = DoubleArray(FEATURE_COUNT)
        var biasGradient = 0.0

        for (example in examples) {
            val vector = toVector(example.features)
            var z = bias
            for (i in vector.indices) z += vector[i] * weights[i]
            val prediction = sigmoid(z)
            val error = prediction - example.label
            for (i in vector.indices) weightGradients[i] += error * vector[i]
            biasGradient += error
        }

        for (i in weights.indices) weights[i] -= (LEARNING_RATE * weightGradients[i]) / n
        bias -= (LEARNING_RATE * biasGradient) / n
    }

    return LogisticModel(weights, bias)
}

/**
 * The default implementation of [RefundFraudRiskScorer] — trains a small logistic regression
 * in-process, once, at construction (Spring beans are singletons by default, so this happens exactly
 * once per process lifetime — the same "train once, cache the weights" role
 * [com.example.accountservice.payment.infrastructure.RefundReasonClassifierImpl]'s lazily-built
 * `httpClient` plays for a different kind of one-time setup) against the synthetic dataset above. A
 * real deployment would retrain periodically against actual refund history instead of training once
 * from a fixed synthetic set at startup. Selected when `fraud-scorer.mode` (`FRAUD_SCORER_MODE`) is
 * `native` or unset — see [com.example.accountservice.config.FraudScorerProperties].
 */
@Component
@ConditionalOnProperty(prefix = "fraud-scorer", name = ["mode"], havingValue = "native", matchIfMissing = true)
class RefundFraudRiskScorerNativeImpl : RefundFraudRiskScorer {
    private val model = trainLogisticRegression(generateTrainingData())

    override fun score(features: RefundRiskFeatures): Double {
        val vector = toVector(features)
        var z = model.bias
        for (i in vector.indices) z += vector[i] * model.weights[i]
        return sigmoid(z)
    }
}
