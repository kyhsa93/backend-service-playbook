package com.example.accountservice.account.domain;

public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);

    void saveAccount(Account account);

    void delete(String accountId);

    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
