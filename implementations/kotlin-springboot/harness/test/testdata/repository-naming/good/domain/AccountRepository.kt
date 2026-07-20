package com.example.accountservice.account.domain

interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun saveAccount(account: Account)

    fun deleteAccount(accountId: String)

    // hasTransactionWithReference처럼 find/save/delete/count로 시작하지 않는 도메인 질의 메서드는
    // blocklist에 걸리지 않아야 한다 (오탐 방지 확인용).
    fun hasTransactionWithReference(referenceId: String): Boolean
}
