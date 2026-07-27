package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.SpendingAnalysis;
import com.example.accountservice.account.domain.SpendingAnalysisRepository;
import com.example.accountservice.account.domain.TransactionSummary;
import com.example.accountservice.account.domain.TransactionSummaryQuery;
import com.example.accountservice.account.domain.TransactionType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A system use case (monthly spending-analysis ETL) invoked once a month by a batch — not a Command
 * invoked directly by a user, but called by {@code
 * interfaces/task/AnalyzeMonthlySpendingTaskController} when it receives a Task Queue message. The
 * ETL, in full: Extract (paginate every ACTIVE account, summarize its and the prior month's
 * WITHDRAWAL transactions), Transform ({@link SpendingAnalysis#create}'s %-change/trend
 * calculation), Load (one row per account per month into spending_analysis). The output is a
 * queryable read-model row, not a file — the value is precomputing an aggregate a client would
 * otherwise have to re-derive from potentially many raw Transaction rows on every request.
 *
 * <p>Mirrors {@code PayInterestService}'s pagination structure. The transaction boundary lives in
 * {@code SpendingAnalysisRepository.saveAnalysis()} (persistence.md), not in this Service — so a
 * failure partway through the batch leaves already-analyzed accounts committed (the next tick picks
 * up the rest; safe because {@code hasAnalysis} makes it idempotent).
 */
@Service
@RequiredArgsConstructor
public class AnalyzeMonthlySpendingService {

    private static final int PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final SpendingAnalysisRepository spendingAnalysisRepository;

    public int analyze(AnalyzeMonthlySpendingCommand command) {
        int analyzedCount = 0;
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
                boolean alreadyAnalyzed =
                        spendingAnalysisRepository.hasAnalysis(
                                account.getAccountId(), command.analysisMonth());
                if (alreadyAnalyzed) {
                    continue;
                }

                TransactionSummary current =
                        accountRepository.summarizeTransactions(
                                new TransactionSummaryQuery(
                                        account.getAccountId(),
                                        List.of(TransactionType.WITHDRAWAL),
                                        command.monthStart(),
                                        command.monthEnd()));
                TransactionSummary previous =
                        accountRepository.summarizeTransactions(
                                new TransactionSummaryQuery(
                                        account.getAccountId(),
                                        List.of(TransactionType.WITHDRAWAL),
                                        command.previousMonthStart(),
                                        command.previousMonthEnd()));

                SpendingAnalysis analysis =
                        SpendingAnalysis.create(
                                account.getAccountId(),
                                command.analysisMonth(),
                                current.totalAmount(),
                                current.count(),
                                previous.totalAmount());

                spendingAnalysisRepository.saveAnalysis(analysis);
                analyzedCount++;
            }

            if (accounts.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return analyzedCount;
    }
}
