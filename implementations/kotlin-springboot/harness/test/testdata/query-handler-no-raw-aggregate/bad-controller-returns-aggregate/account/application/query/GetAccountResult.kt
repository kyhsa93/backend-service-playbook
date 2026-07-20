package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import org.springframework.stereotype.Service

@Service
class GetAccountService {
    fun getAccountEntity(accountId: String): Account = Account.create()
}
