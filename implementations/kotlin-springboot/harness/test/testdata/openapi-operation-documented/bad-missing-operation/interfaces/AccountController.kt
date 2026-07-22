package com.example.accountservice.account.interfaces.rest

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// No @Operation at all — an operationId-less, summary-less, description-less bare route.
@RestController
@RequestMapping("/accounts")
class AccountController {
    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable accountId: String,
    ): String = accountId
}
