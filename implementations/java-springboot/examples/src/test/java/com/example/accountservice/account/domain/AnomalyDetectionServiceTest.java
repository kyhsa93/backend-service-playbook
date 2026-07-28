package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AnomalyDetectionService is a Domain Service that carries no framework annotations, so it is
 * instantiated directly with {@code new} (no Spring context) to verify only the outlier-detection
 * logic — the same pattern as {@link TransferEligibilityServiceTest}.
 */
class AnomalyDetectionServiceTest {

    private final AnomalyDetectionService service = new AnomalyDetectionService();

    @Test
    void
            isAnomalous_when_history_has_fewer_than_5_withdrawals_then_returns_false_regardless_of_amount() {
        boolean result = service.isAnomalous(List.of(10000L, 10000L, 10000L, 10000L), 5000000L);

        assertThat(result).isFalse();
    }

    @Test
    void isAnomalous_when_the_amount_is_close_to_the_historical_mean_then_returns_false() {
        List<Long> history = List.of(10000L, 12000L, 9000L, 11000L, 10500L, 9500L);

        boolean result = service.isAnomalous(history, 10800L);

        assertThat(result).isFalse();
    }

    @Test
    void isAnomalous_when_the_amount_is_far_beyond_the_historical_spread_then_returns_true() {
        List<Long> history = List.of(10000L, 12000L, 9000L, 11000L, 10500L, 9500L);

        boolean result = service.isAnomalous(history, 5000000L);

        assertThat(result).isTrue();
    }

    @Test
    void
            isAnomalous_when_history_is_perfectly_uniform_and_the_amount_matches_it_then_returns_false() {
        boolean result =
                service.isAnomalous(List.of(10000L, 10000L, 10000L, 10000L, 10000L), 10000L);

        assertThat(result).isFalse();
    }

    @Test
    void isAnomalous_when_history_is_perfectly_uniform_and_the_amount_differs_then_returns_true() {
        boolean result =
                service.isAnomalous(List.of(10000L, 10000L, 10000L, 10000L, 10000L), 10001L);

        assertThat(result).isTrue();
    }
}
