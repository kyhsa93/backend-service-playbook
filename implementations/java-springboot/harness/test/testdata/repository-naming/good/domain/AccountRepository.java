package com.example.accountservice.account.domain;

public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);

    void saveAccount(Account account);

    void deleteAccount(String accountId);

    boolean hasTransactionWithReference(String referenceId, String type);
}
