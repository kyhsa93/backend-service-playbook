package com.example.accountservice.account.domain

sealed class AccountException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : AccountException("account not found: $accountId")

class AccountService {
    fun requireFound(account: Any?, accountId: String) {
        if (account == null) throw AccountNotFoundException(accountId)
    }
}
