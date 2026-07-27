package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.SpendingAnalysis;
import com.example.accountservice.account.domain.SpendingAnalysisRepository;
import com.example.accountservice.account.domain.SpendingTrend;
import com.example.accountservice.account.domain.TransactionSummary;
import com.example.accountservice.account.domain.TransactionSummaryQuery;
import com.example.accountservice.account.domain.TransactionType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyzeMonthlySpendingServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private SpendingAnalysisRepository spendingAnalysisRepository;

    private AnalyzeMonthlySpendingService service;

    private static final LocalDateTime MONTH_START = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final LocalDateTime MONTH_END = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime PREVIOUS_MONTH_START = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime PREVIOUS_MONTH_END = MONTH_START;

    @BeforeEach
    void setUp() {
        service = new AnalyzeMonthlySpendingService(accountRepository, spendingAnalysisRepository);
    }

    private Account activeAccount() {
        return Account.create("owner-1", "owner-1@example.com", "KRW");
    }

    private AnalyzeMonthlySpendingCommand command() {
        return new AnalyzeMonthlySpendingCommand(
                "2026-07", MONTH_START, MONTH_END, PREVIOUS_MONTH_START, PREVIOUS_MONTH_END);
    }

    private void stubAccounts(Account account) {
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(account), 1));
    }

    @Test
    void summarizes_current_and_previous_month_with_the_right_date_ranges_and_saves_the_analysis() {
        Account account = activeAccount();
        stubAccounts(account);
        when(spendingAnalysisRepository.hasAnalysis(account.getAccountId(), "2026-07"))
                .thenReturn(false);
        when(accountRepository.summarizeTransactions(
                        new TransactionSummaryQuery(
                                account.getAccountId(),
                                List.of(TransactionType.WITHDRAWAL),
                                MONTH_START,
                                MONTH_END)))
                .thenReturn(new TransactionSummary(2, 50000));
        when(accountRepository.summarizeTransactions(
                        new TransactionSummaryQuery(
                                account.getAccountId(),
                                List.of(TransactionType.WITHDRAWAL),
                                PREVIOUS_MONTH_START,
                                PREVIOUS_MONTH_END)))
                .thenReturn(new TransactionSummary(0, 0));

        int analyzedCount = service.analyze(command());

        assertThat(analyzedCount).isEqualTo(1);
        ArgumentCaptor<SpendingAnalysis> captor = ArgumentCaptor.forClass(SpendingAnalysis.class);
        verify(spendingAnalysisRepository, times(1)).saveAnalysis(captor.capture());
        SpendingAnalysis saved = captor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(account.getAccountId());
        assertThat(saved.getAnalysisMonth()).isEqualTo("2026-07");
        assertThat(saved.getTotalAmount()).isEqualTo(50000);
        assertThat(saved.getTransactionCount()).isEqualTo(2);
        assertThat(saved.getAverageAmount()).isEqualTo(25000);
        // No prior-month withdrawal history exists, so the comparison baseline is 0 — the
        // %-change is capped at 100 and the trend is INCREASING (see SpendingAnalysis.create).
        assertThat(saved.getChangeFromPreviousMonth()).isEqualTo(100);
        assertThat(saved.getTrend()).isEqualTo(SpendingTrend.INCREASING);
    }

    @Test
    void skips_an_account_that_has_already_been_analyzed_this_month() {
        Account account = activeAccount();
        stubAccounts(account);
        when(spendingAnalysisRepository.hasAnalysis(account.getAccountId(), "2026-07"))
                .thenReturn(true);

        int analyzedCount = service.analyze(command());

        assertThat(analyzedCount).isEqualTo(0);
        verify(accountRepository, never()).summarizeTransactions(any());
        verify(spendingAnalysisRepository, never()).saveAnalysis(any());
    }

    @Test
    void does_nothing_when_there_are_no_active_accounts() {
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        int analyzedCount = service.analyze(command());

        assertThat(analyzedCount).isEqualTo(0);
        verify(spendingAnalysisRepository, never()).saveAnalysis(any());
    }
}
