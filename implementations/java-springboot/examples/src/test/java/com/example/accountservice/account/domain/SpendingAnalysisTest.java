package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpendingAnalysisTest {

    @Test
    void create_when_spending_increased_by_more_than_10_percent_then_trend_is_INCREASING() {
        SpendingAnalysis analysis =
                SpendingAnalysis.create("account-1", "2026-07", 15000, 3, 10000);

        assertThat(analysis.getChangeFromPreviousMonth()).isEqualTo(50);
        assertThat(analysis.getTrend()).isEqualTo(SpendingTrend.INCREASING);
        assertThat(analysis.getAverageAmount()).isEqualTo(5000);
    }

    @Test
    void create_when_spending_decreased_by_more_than_10_percent_then_trend_is_DECREASING() {
        SpendingAnalysis analysis = SpendingAnalysis.create("account-1", "2026-07", 5000, 1, 10000);

        assertThat(analysis.getChangeFromPreviousMonth()).isEqualTo(-50);
        assertThat(analysis.getTrend()).isEqualTo(SpendingTrend.DECREASING);
    }

    @Test
    void create_when_the_change_is_within_10_percent_then_trend_is_STABLE() {
        SpendingAnalysis analysis =
                SpendingAnalysis.create("account-1", "2026-07", 10500, 2, 10000);

        assertThat(analysis.getChangeFromPreviousMonth()).isEqualTo(5);
        assertThat(analysis.getTrend()).isEqualTo(SpendingTrend.STABLE);
    }

    @Test
    void create_when_there_was_no_spending_in_either_month_then_0_percent_change_and_STABLE() {
        SpendingAnalysis analysis = SpendingAnalysis.create("account-1", "2026-07", 0, 0, 0);

        assertThat(analysis.getChangeFromPreviousMonth()).isEqualTo(0);
        assertThat(analysis.getTrend()).isEqualTo(SpendingTrend.STABLE);
        assertThat(analysis.getAverageAmount()).isEqualTo(0);
    }

    @Test
    void
            create_when_there_was_no_spending_last_month_but_spending_this_month_then_100_percent_change_and_INCREASING() {
        SpendingAnalysis analysis = SpendingAnalysis.create("account-1", "2026-07", 3000, 1, 0);

        assertThat(analysis.getChangeFromPreviousMonth()).isEqualTo(100);
        assertThat(analysis.getTrend()).isEqualTo(SpendingTrend.INCREASING);
    }

    @Test
    void create_when_transactionCount_is_0_then_averageAmount_is_0_rather_than_dividing_by_zero() {
        SpendingAnalysis analysis = SpendingAnalysis.create("account-1", "2026-07", 0, 0, 5000);

        assertThat(analysis.getAverageAmount()).isEqualTo(0);
    }
}
