package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetTransactionsService {

    private final AccountQuery accountQuery;

    public GetTransactionsResult getTransactions(String accountId, String requesterId, int page, int take) {
        accountQuery.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));

        List<Transaction> transactions = accountQuery.findTransactions(accountId, page, take);
        long count = accountQuery.countTransactions(accountId);

        List<GetTransactionsResult.TransactionSummary> summaries = transactions.stream()
                .map(t -> new GetTransactionsResult.TransactionSummary(
                        t.getTransactionId(),
                        t.getType().name(),
                        new GetTransactionsResult.MoneyResult(t.getAmount().amount(), t.getAmount().currency()),
                        t.getCreatedAt()))
                .toList();

        return new GetTransactionsResult(summaries, count);
    }
}
