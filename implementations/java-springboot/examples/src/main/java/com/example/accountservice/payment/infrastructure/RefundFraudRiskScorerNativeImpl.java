package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.payment.application.service.RefundFraudRiskScorer;
import com.example.accountservice.payment.domain.RefundRiskFeatures;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default implementation of {@link RefundFraudRiskScorer} — trains a small logistic regression
 * in-process, once, at construction time (this bean is a singleton), against a synthetic dataset
 * that mirrors the ground-truth rule in {@code services/fraud-risk-scorer/model.py} (the shared
 * HTTP alternative, see {@link RefundFraudRiskScorerHttpImpl}), so both sides of the "shared
 * service vs. native" pair are trained on equivalent data. No external ML library — hand-rolled
 * batch gradient descent, deliberately simple/inspectable (see root
 * docs/architecture/domain-service.md's RefundFraudRiskScorer example). Self-contained; no extra
 * service needed, which is why {@code native} is the default ({@code fraud-scorer.mode=native}, see
 * {@code FraudScorerProperties}). A real deployment would retrain periodically against actual
 * refund history instead of training once from a fixed synthetic set at startup.
 */
@Component
@ConditionalOnProperty(
        prefix = "fraud-scorer",
        name = "mode",
        havingValue = "native",
        matchIfMissing = true)
public class RefundFraudRiskScorerNativeImpl implements RefundFraudRiskScorer {

    private static final int TRAINING_EXAMPLE_COUNT = 300;
    private static final long TRAINING_SEED = 42L;
    private static final int FEATURE_COUNT = 4;
    private static final double LEARNING_RATE = 0.5;
    private static final int EPOCHS = 500;

    private record TrainingExample(RefundRiskFeatures features, double label) {}

    private record Model(double[] weights, double bias) {}

    // Trained once, here, at construction — reused for every score() call for the lifetime of
    // this singleton bean.
    private final Model model = trainLogisticRegression(generateTrainingData());

    @Override
    public double score(RefundRiskFeatures features) {
        double[] vector = toVector(features);
        double z = model.bias();
        for (int i = 0; i < vector.length; i++) {
            z += vector[i] * model.weights()[i];
        }
        return sigmoid(z);
    }

    // A synthetic seed dataset standing in for real historical fraud-review outcomes — this
    // example has no real user base to draw labeled data from. The label follows an explicit
    // ground-truth rule (frequent + high-ratio + fast-after-payment refunds are risky) purely so
    // the model has a non-trivial pattern to fit; replace this with real labeled history in
    // production. java.util.Random(42) makes the generated dataset — and therefore the trained
    // weights — identical on every run.
    private static List<TrainingExample> generateTrainingData() {
        Random random = new Random(TRAINING_SEED);
        List<TrainingExample> examples = new ArrayList<>(TRAINING_EXAMPLE_COUNT);
        for (int i = 0; i < TRAINING_EXAMPLE_COUNT; i++) {
            int refundCount = random.nextInt(8);
            int rejectedCount = random.nextInt(4);
            double ratio = random.nextDouble();
            double minutes = random.nextDouble() * 43200;
            double riskScore =
                    refundCount * 0.15
                            + rejectedCount * 0.3
                            + ratio * 0.4
                            + Math.max(0, 1 - minutes / 1440) * 0.3;
            double label = riskScore > 1.1 ? 1 : 0;
            examples.add(
                    new TrainingExample(
                            new RefundRiskFeatures(refundCount, rejectedCount, ratio, minutes),
                            label));
        }
        return examples;
    }

    // Scales each raw feature into a roughly 0-1 range before it reaches the model — plain
    // gradient descent converges far more slowly (and less reliably) on unscaled inputs this
    // different in magnitude (a single-digit count next to a value in the thousands). Mirrored
    // exactly in services/fraud-risk-scorer/model.py's to_vector.
    private static double[] toVector(RefundRiskFeatures features) {
        return new double[] {
            features.refundCountLast30Days() / 10.0,
            features.rejectedRefundCountLast30Days() / 5.0,
            features.refundToPaymentAmountRatio(),
            Math.min(1, features.minutesSincePayment() / 1440)
        };
    }

    private static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    // Batch gradient descent on plain logistic regression — no external ML library, deliberately
    // simple/inspectable. This is the "each language trains natively" side of the pair;
    // RefundFraudRiskScorerHttpImpl (the shared services/fraud-risk-scorer microservice) is the
    // "one shared service" side.
    private static Model trainLogisticRegression(List<TrainingExample> examples) {
        double[] weights = new double[FEATURE_COUNT];
        double bias = 0;
        int n = examples.size();

        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            double[] weightGradients = new double[FEATURE_COUNT];
            double biasGradient = 0;

            for (TrainingExample example : examples) {
                double[] vector = toVector(example.features());
                double z = bias;
                for (int i = 0; i < vector.length; i++) {
                    z += vector[i] * weights[i];
                }
                double prediction = sigmoid(z);
                double error = prediction - example.label();
                for (int i = 0; i < vector.length; i++) {
                    weightGradients[i] += error * vector[i];
                }
                biasGradient += error;
            }

            for (int i = 0; i < weights.length; i++) {
                weights[i] -= (LEARNING_RATE * weightGradients[i]) / n;
            }
            bias -= (LEARNING_RATE * biasGradient) / n;
        }

        return new Model(weights, bias);
    }
}
