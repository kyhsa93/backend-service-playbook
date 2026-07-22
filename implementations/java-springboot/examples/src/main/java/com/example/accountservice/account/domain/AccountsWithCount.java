package com.example.accountservice.account.domain;

import java.util.List;

/**
 * The result of a {@code findAccounts} lookup — returns the list together with the total count.
 * Single- record lookups also reuse this type: call it with {@code AccountFindQuery.take} set to 1,
 * then take the first result from {@code accounts()} (see repository-pattern.md).
 */
public record AccountsWithCount(List<Account> accounts, long count) {}
