package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account

interface AccountQuery {
    fun findAccounts(): List<Account>
}
