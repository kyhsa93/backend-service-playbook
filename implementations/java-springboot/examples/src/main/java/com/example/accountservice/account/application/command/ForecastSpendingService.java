package com.example.accountservice.account.application.command;

import com.example.accountservice.account.application.service.SpendingForecastModel;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.SpendingAnalysis;
import com.example.accountservice.account.domain.SpendingAnalysisRepository;
import com.example.accountservice.account.domain.SpendingForecast;
import com.example.accountservice.account.domain.SpendingForecastRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A system use case (monthly spending-forecast training) invoked once a month by a batch — not a
 * Command invoked directly by a user, but called by {@code
 * interfaces/task/ForecastSpendingTaskController} when it receives a Task Queue message. Trains
 * (fits) a fresh {@link SpendingForecastModel} per account from that account's own
 * spending_analysis history on every run — there is no persisted "model weights" row separate from
 * the forecast itself, the same simplicity tradeoff the ETL job upstream ({@code
 * AnalyzeMonthlySpendingService}) makes (recomputed monthly, not maintained incrementally).
 *
 * <p>Mirrors {@code AnalyzeMonthlySpendingService}'s pagination structure. The transaction boundary
 * lives in {@code SpendingForecastRepository.saveForecast()} (persistence.md), not in this Service.
 */
@Service
@RequiredArgsConstructor
public class ForecastSpendingService {

    private static final int PAGE_SIZE = 100;

    // A cold-start guard, not a tuning knob: 2 points make any line "fit" perfectly (R^2 == 1
    // regardless of the actual trend), so 3 is the minimum for SpendingForecastModel's R^2 to mean
    // anything. An account younger than 3 analyzed months is simply skipped and retried by next
    // month's run once it has more history — the same "skip, don't fail" idempotency-adjacent
    // posture as AnalyzeMonthlySpendingService skipping an already-analyzed account.
    private static final int MIN_HISTORY_MONTHS_FOR_FORECAST = 3;
    private static final int MAX_HISTORY_MONTHS_FOR_FORECAST = 6;

    private final AccountRepository accountRepository;
    private final SpendingAnalysisRepository spendingAnalysisRepository;
    private final SpendingForecastRepository spendingForecastRepository;
    private final SpendingForecastModel spendingForecastModel;

    public int forecast(ForecastSpendingCommand command) {
        int forecastedCount = 0;
        int page = 0;
        while (true) {
            List<Account> accounts =
                    accountRepository
                            .findAccounts(
                                    new AccountFindQuery(
                                            page,
                                            PAGE_SIZE,
                                            null,
                                            null,
                                            List.of(AccountStatus.ACTIVE.name())))
                            .accounts();
            if (accounts.isEmpty()) {
                break;
            }

            for (Account account : accounts) {
                boolean alreadyForecasted =
                        spendingForecastRepository.hasForecast(
                                account.getAccountId(), command.forecastMonth());
                if (alreadyForecasted) {
                    continue;
                }

                List<SpendingAnalysis> history =
                        spendingAnalysisRepository.findRecentAnalyses(
                                account.getAccountId(),
                                command.forecastMonth(),
                                MAX_HISTORY_MONTHS_FOR_FORECAST);
                if (history.size() < MIN_HISTORY_MONTHS_FOR_FORECAST) {
                    continue;
                }

                SpendingForecastModel.Prediction prediction =
                        spendingForecastModel.predict(
                                history.stream()
                                        .map(
                                                analysis ->
                                                        new SpendingForecastModel.HistoryPoint(
                                                                analysis.getAnalysisMonth(),
                                                                analysis.getTotalAmount()))
                                        .toList());

                SpendingForecast forecast =
                        SpendingForecast.create(
                                account.getAccountId(),
                                command.forecastMonth(),
                                prediction.predictedAmount(),
                                prediction.confidence(),
                                history.size());

                spendingForecastRepository.saveForecast(forecast);
                forecastedCount++;
            }

            if (accounts.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return forecastedCount;
    }
}
