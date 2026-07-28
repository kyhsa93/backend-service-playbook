package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.SpendingForecast;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account (unlike Payment/Refund) has an ownerId column directly on it, so ownership is verified
 * with a single {@code AccountQuery.findAccounts} lookup — mirrors {@code
 * GetSpendingAnalysisService}'s structure.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetSpendingForecastService {

    private final AccountQuery accountQuery;
    private final SpendingForecastQuery spendingForecastQuery;

    public SpendingForecastResult getSpendingForecast(
            String accountId, String requesterId, String forecastMonth) {
        accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts()
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new AccountException(
                                        AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                        "Account not found."));

        SpendingForecast forecast =
                spendingForecastQuery
                        .findForecast(accountId, forecastMonth)
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode
                                                        .SPENDING_FORECAST_NOT_FOUND,
                                                "Spending forecast not found."));

        return new SpendingForecastResult(
                forecast.getForecastMonth(),
                forecast.getPredictedAmount(),
                forecast.getConfidence().name(),
                forecast.getHistoryMonthsUsed(),
                forecast.getCreatedAt());
    }
}
