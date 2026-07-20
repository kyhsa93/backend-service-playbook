package com.example.accountservice.account.domain

interface AccountRepository {
    fun findAll(): List<Account>

    fun saveAccount(account: Account)
}
