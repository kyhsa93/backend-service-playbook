package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.SpendingAnalysis;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account (unlike Payment/Refund) has an ownerId column directly on it, so ownership is verified
 * with a single {@code AccountQuery.findAccounts} lookup — simpler than Payment/Refund's two-hop
 * verification. Mirrors {@code GetAccountService}'s structure.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetSpendingAnalysisService {

    private final AccountQuery accountQuery;
    private final SpendingAnalysisQuery spendingAnalysisQuery;

    public SpendingAnalysisResult getSpendingAnalysis(
            String accountId, String requesterId, String analysisMonth) {
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

        SpendingAnalysis analysis =
                spendingAnalysisQuery
                        .findAnalysis(accountId, analysisMonth)
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode
                                                        .SPENDING_ANALYSIS_NOT_FOUND,
                                                "Spending analysis not found."));

        return new SpendingAnalysisResult(
                analysis.getAnalysisMonth(),
                analysis.getTotalAmount(),
                analysis.getTransactionCount(),
                analysis.getAverageAmount(),
                analysis.getChangeFromPreviousMonth(),
                analysis.getTrend().name(),
                analysis.getCreatedAt());
    }
}
