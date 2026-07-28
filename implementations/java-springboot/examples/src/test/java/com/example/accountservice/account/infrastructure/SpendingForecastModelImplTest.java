package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.account.application.service.SpendingForecastModel;
import com.example.accountservice.account.domain.ForecastConfidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpendingForecastModelImplTest {

    private final SpendingForecastModelImpl model = new SpendingForecastModelImpl();

    @Test
    void predicts_exactly_and_with_high_confidence_for_a_perfect_linear_trend() {
        SpendingForecastModel.Prediction prediction =
                model.predict(
                        List.of(
                                new SpendingForecastModel.HistoryPoint("2026-04", 10000),
                                new SpendingForecastModel.HistoryPoint("2026-05", 20000),
                                new SpendingForecastModel.HistoryPoint("2026-06", 30000)));

        assertThat(prediction.predictedAmount()).isEqualTo(40000);
        assertThat(prediction.confidence()).isEqualTo(ForecastConfidence.HIGH);
    }

    @Test
    void predicts_the_same_amount_with_high_confidence_for_a_perfectly_flat_history() {
        SpendingForecastModel.Prediction prediction =
                model.predict(
                        List.of(
                                new SpendingForecastModel.HistoryPoint("2026-04", 15000),
                                new SpendingForecastModel.HistoryPoint("2026-05", 15000),
                                new SpendingForecastModel.HistoryPoint("2026-06", 15000)));

        assertThat(prediction.predictedAmount()).isEqualTo(15000);
        assertThat(prediction.confidence()).isEqualTo(ForecastConfidence.HIGH);
    }

    @Test
    void reports_lower_confidence_for_noisy_non_linear_history() {
        SpendingForecastModel.Prediction prediction =
                model.predict(
                        List.of(
                                new SpendingForecastModel.HistoryPoint("2026-01", 5000),
                                new SpendingForecastModel.HistoryPoint("2026-02", 40000),
                                new SpendingForecastModel.HistoryPoint("2026-03", 3000),
                                new SpendingForecastModel.HistoryPoint("2026-04", 35000),
                                new SpendingForecastModel.HistoryPoint("2026-05", 4000),
                                new SpendingForecastModel.HistoryPoint("2026-06", 38000)));

        assertThat(prediction.confidence()).isNotEqualTo(ForecastConfidence.HIGH);
    }

    @Test
    void floors_the_prediction_at_0_instead_of_going_negative_for_a_sharply_decreasing_trend() {
        SpendingForecastModel.Prediction prediction =
                model.predict(
                        List.of(
                                new SpendingForecastModel.HistoryPoint("2026-04", 30000),
                                new SpendingForecastModel.HistoryPoint("2026-05", 15000),
                                new SpendingForecastModel.HistoryPoint("2026-06", 1000)));

        assertThat(prediction.predictedAmount()).isEqualTo(0);
    }
}
