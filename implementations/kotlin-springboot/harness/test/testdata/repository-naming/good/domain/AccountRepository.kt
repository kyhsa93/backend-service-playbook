package com.example.accountservice.account.domain

interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun saveAccount(account: Account)

    fun deleteAccount(accountId: String)

    // A domain query method that doesn't start with find/save/delete/count, like
    // hasTransactionWithReference, must not trip the blocklist (verifies false-positive avoidance).
    fun hasTransactionWithReference(referenceId: String): Boolean
}
