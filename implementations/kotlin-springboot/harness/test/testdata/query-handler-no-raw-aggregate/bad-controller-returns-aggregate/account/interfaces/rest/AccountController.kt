package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.application.query.GetAccountService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

// Violation — the Controller returns the raw Account Aggregate as-is instead of a dedicated Result data class.
@RestController
class AccountController(
    private val getAccountService: GetAccountService,
) {
    @GetMapping("/accounts/{accountId}")
    fun getAccount(
        @PathVariable accountId: String,
    ): Account = getAccountService.getAccountEntity(accountId)
}
