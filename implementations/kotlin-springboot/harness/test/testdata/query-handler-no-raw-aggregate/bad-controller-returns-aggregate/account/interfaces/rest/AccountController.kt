package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.application.query.GetAccountService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

// 위반 — Controller가 전용 Result data class 대신 raw Account Aggregate를 그대로 반환한다.
@RestController
class AccountController(
    private val getAccountService: GetAccountService,
) {
    @GetMapping("/accounts/{accountId}")
    fun getAccount(
        @PathVariable accountId: String,
    ): Account = getAccountService.getAccountEntity(accountId)
}
