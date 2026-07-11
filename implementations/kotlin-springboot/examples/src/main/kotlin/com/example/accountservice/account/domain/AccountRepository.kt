package com.example.accountservice.account.domain

interface AccountRepository {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?
    fun findAll(query: AccountFindQuery): List<Account>
    fun countAll(query: AccountFindQuery): Long
    fun save(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction>
    fun countTransactions(accountId: String): Long
}

data class AccountFindQuery(
    val page: Int,
    val take: Int,
    val accountId: String? = null,
    val ownerId: String? = null,
    val status: List<String>? = null,
)
