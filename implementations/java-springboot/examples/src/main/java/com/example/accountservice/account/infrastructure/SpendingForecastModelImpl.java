package com.example.accountservice.account.infrastructure;

import com.example.accountservice.account.application.service.SpendingForecastModel;
import com.example.accountservice.account.domain.ForecastConfidence;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Ordinary least squares over (monthIndex, totalAmount) — a genuine trained model (its two
 * parameters, slope and intercept, are fit fresh from each account's own history every time the
 * scheduled job runs) rather than a hardcoded rule, while staying dependency-free and explainable.
 * x is just the position of the month within the trailing history (0, 1, 2, ...), not a calendar
 * value, so a gap month (an account with no analysis for some month) doesn't skew the fit — callers
 * pass in only the months that actually exist.
 */
@Component
public class SpendingForecastModelImpl implements SpendingForecastModel {

    private static final double HIGH_CONFIDENCE_R_SQUARED = 0.7;
    private static final double MEDIUM_CONFIDENCE_R_SQUARED = 0.3;

    @Override
    public Prediction predict(List<HistoryPoint> history) {
        int n = history.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i;
            ys[i] = history.get(i).totalAmount();
        }

        double xMean = average(xs);
        double yMean = average(ys);

        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (xs[i] - xMean) * (ys[i] - yMean);
            denominator += (xs[i] - xMean) * (xs[i] - xMean);
        }
        // denominator is 0 only when n == 1, which MIN_HISTORY_MONTHS_FOR_FORECAST (>= 3) already
        // rules out for every real caller — guarded here anyway so this stays correct in isolation.
        double slope = denominator == 0 ? 0 : numerator / denominator;
        double intercept = yMean - slope * xMean;

        int nextMonthIndex = n;
        long predictedAmount = Math.max(0, Math.round(intercept + slope * nextMonthIndex));

        double ssTotal = 0;
        double ssResidual = 0;
        for (int i = 0; i < n; i++) {
            ssTotal += (ys[i] - yMean) * (ys[i] - yMean);
            double predicted = intercept + slope * xs[i];
            ssResidual += (ys[i] - predicted) * (ys[i] - predicted);
        }
        // A perfectly flat history (ssTotal == 0) is a perfect fit by definition, not an undefined
        // one — 0/0 would otherwise produce NaN.
        double rSquared = ssTotal == 0 ? 1 : 1 - ssResidual / ssTotal;

        ForecastConfidence confidence = ForecastConfidence.LOW;
        if (rSquared >= HIGH_CONFIDENCE_R_SQUARED) {
            confidence = ForecastConfidence.HIGH;
        } else if (rSquared >= MEDIUM_CONFIDENCE_R_SQUARED) {
            confidence = ForecastConfidence.MEDIUM;
        }

        return new Prediction(predictedAmount, confidence);
    }

    private static double average(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }
}
