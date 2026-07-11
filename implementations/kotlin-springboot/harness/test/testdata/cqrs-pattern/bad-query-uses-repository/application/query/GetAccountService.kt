package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service

@Service
class GetAccountService(private val accountRepository: AccountRepository) {
    fun getAccount(accountId: String, requesterId: String): Any? =
        accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
}
