package com.example.accountservice.account.domain

interface AccountRepository {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?

    fun saveAccount(account: Account)
}
