package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.Account;

import java.util.Optional;

/**
 * A read-only interface dedicated to the Query Service. A narrow contract separated from
 * the write-side {@code AccountRepository} (domain). This Javadoc deliberately mentions
 * the write-side interface's name for documentation purposes — since the actual code
 * (excluding comments) has no Repository reference, the rule must still pass.
 */
public interface AccountQuery {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
}
