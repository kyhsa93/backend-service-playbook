package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(
    private val accountRepositoryImpl: AccountRepositoryImpl,
)
