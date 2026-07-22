package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account

// A read-only port interface with no @Service — since it's used only inside the Query Service,
// returning a raw Account is not targeted(cqrs-pattern.md).
interface AccountQuery {
    fun findAccounts(): List<Account>
}
