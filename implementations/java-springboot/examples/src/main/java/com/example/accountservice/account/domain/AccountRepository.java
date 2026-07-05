package com.example.accountservice.account.domain;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
    List<Account> findAll(AccountFindQuery query);
    long countAll(AccountFindQuery query);
    void save(Account account);
    List<Transaction> findTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}
