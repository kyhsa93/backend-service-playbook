package com.example.accountservice.account.application.query

/**
 * A read-only port separated from the write-model AccountRepository — the mention of "Repository"
 * inside this comment is not a real code dependency, so like other rules this rule must ignore
 * comments too.
 */
interface AccountQuery {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
}
