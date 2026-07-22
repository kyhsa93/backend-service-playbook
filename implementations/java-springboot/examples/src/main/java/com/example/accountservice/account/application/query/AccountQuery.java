package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.TransactionsWithCount;

/**
 * A read-only interface dedicated to the Query Service. It is a narrow contract kept separate from
 * the write-side {@code AccountRepository} (domain). The Query Service must depend only on this
 * interface — it does not expose write methods such as {@code saveAccount}/{@code delete} (see
 * cqrs-pattern.md).
 */
public interface AccountQuery {
    AccountsWithCount findAccounts(AccountFindQuery query);

    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
