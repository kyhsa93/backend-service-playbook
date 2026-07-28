package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.service.SpendingForecastModel;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.ForecastConfidence;
import com.example.accountservice.account.domain.SpendingAnalysis;
import com.example.accountservice.account.domain.SpendingAnalysisRepository;
import com.example.accountservice.account.domain.SpendingForecast;
import com.example.accountservice.account.domain.SpendingForecastRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastSpendingServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private SpendingAnalysisRepository spendingAnalysisRepository;
    @Mock private SpendingForecastRepository spendingForecastRepository;
    @Mock private SpendingForecastModel spendingForecastModel;

    private ForecastSpendingService service;

    @BeforeEach
    void setUp() {
        service =
                new ForecastSpendingService(
                        accountRepository,
                        spendingAnalysisRepository,
                        spendingForecastRepository,
                        spendingForecastModel);
    }

    private Account activeAccount() {
        return Account.create("owner-1", "owner-1@example.com", "KRW");
    }

    private ForecastSpendingCommand command() {
        return new ForecastSpendingCommand("2026-07");
    }

    private void stubAccounts(Account account) {
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(account), 1));
    }

    private List<SpendingAnalysis> threeMonthsHistory(String accountId) {
        return List.of(
                SpendingAnalysis.create(accountId, "2026-04", 10000, 1, 0),
                SpendingAnalysis.create(accountId, "2026-05", 20000, 1, 10000),
                SpendingAnalysis.create(accountId, "2026-06", 30000, 1, 20000));
    }

    @Test
    void trains_and_saves_a_forecast_for_an_account_with_at_least_3_months_of_history() {
        Account account = activeAccount();
        stubAccounts(account);
        when(spendingForecastRepository.hasForecast(account.getAccountId(), "2026-07"))
                .thenReturn(false);
        List<SpendingAnalysis> history = threeMonthsHistory(account.getAccountId());
        when(spendingAnalysisRepository.findRecentAnalyses(account.getAccountId(), "2026-07", 6))
                .thenReturn(history);
        when(spendingForecastModel.predict(any()))
                .thenReturn(new SpendingForecastModel.Prediction(40000, ForecastConfidence.HIGH));

        int forecastedCount = service.forecast(command());

        assertThat(forecastedCount).isEqualTo(1);

        ArgumentCaptor<List<SpendingForecastModel.HistoryPoint>> historyCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(spendingForecastModel).predict(historyCaptor.capture());
        assertThat(historyCaptor.getValue())
                .containsExactly(
                        new SpendingForecastModel.HistoryPoint("2026-04", 10000),
                        new SpendingForecastModel.HistoryPoint("2026-05", 20000),
                        new SpendingForecastModel.HistoryPoint("2026-06", 30000));

        ArgumentCaptor<SpendingForecast> forecastCaptor =
                ArgumentCaptor.forClass(SpendingForecast.class);
        verify(spendingForecastRepository, times(1)).saveForecast(forecastCaptor.capture());
        SpendingForecast saved = forecastCaptor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(account.getAccountId());
        assertThat(saved.getForecastMonth()).isEqualTo("2026-07");
        assertThat(saved.getPredictedAmount()).isEqualTo(40000);
        assertThat(saved.getConfidence()).isEqualTo(ForecastConfidence.HIGH);
        assertThat(saved.getHistoryMonthsUsed()).isEqualTo(3);
    }

    @Test
    void skips_an_account_with_fewer_than_3_months_of_history_without_training() {
        Account account = activeAccount();
        stubAccounts(account);
        when(spendingForecastRepository.hasForecast(account.getAccountId(), "2026-07"))
                .thenReturn(false);
        when(spendingAnalysisRepository.findRecentAnalyses(account.getAccountId(), "2026-07", 6))
                .thenReturn(threeMonthsHistory(account.getAccountId()).subList(0, 2));

        int forecastedCount = service.forecast(command());

        assertThat(forecastedCount).isEqualTo(0);
        verify(spendingForecastModel, never()).predict(any());
        verify(spendingForecastRepository, never()).saveForecast(any());
    }

    @Test
    void skips_an_account_that_already_has_a_forecast_for_the_month() {
        Account account = activeAccount();
        stubAccounts(account);
        when(spendingForecastRepository.hasForecast(account.getAccountId(), "2026-07"))
                .thenReturn(true);

        int forecastedCount = service.forecast(command());

        assertThat(forecastedCount).isEqualTo(0);
        verify(spendingAnalysisRepository, never())
                .findRecentAnalyses(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(spendingForecastRepository, never()).saveForecast(any());
    }
}
