package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.application.command.CreateAccountService
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(
    private val createAccountService: CreateAccountService,
)
