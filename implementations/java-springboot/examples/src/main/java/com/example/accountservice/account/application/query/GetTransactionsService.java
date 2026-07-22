package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionsWithCount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetTransactionsService {

    private final AccountQuery accountQuery;

    public GetTransactionsResult getTransactions(
            String accountId, String requesterId, int page, int take) {
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

        TransactionsWithCount result = accountQuery.findTransactions(accountId, page, take);
        List<Transaction> transactions = result.transactions();
        long count = result.count();

        List<GetTransactionsResult.TransactionSummary> summaries =
                transactions.stream()
                        .map(
                                t ->
                                        new GetTransactionsResult.TransactionSummary(
                                                t.getTransactionId(),
                                                t.getType().name(),
                                                new GetTransactionsResult.MoneyResult(
                                                        t.getAmount().amount(),
                                                        t.getAmount().currency()),
                                                t.getCreatedAt()))
                        .toList();

        return new GetTransactionsResult(summaries, count);
    }
}
