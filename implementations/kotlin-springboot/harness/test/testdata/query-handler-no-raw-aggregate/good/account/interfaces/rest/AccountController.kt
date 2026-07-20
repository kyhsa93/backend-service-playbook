package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.application.query.GetAccountResult
import com.example.accountservice.account.application.query.GetAccountService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(
    private val getAccountService: GetAccountService,
) {
    @GetMapping("/accounts/{accountId}")
    fun getAccount(
        @PathVariable accountId: String,
    ): GetAccountResult = getAccountService.getAccount(accountId)
}
