package com.example.accountservice.account.domain

interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun saveAccount(account: Account)

    fun updateAccount(account: Account)
}
