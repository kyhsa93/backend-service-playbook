package com.example.accountservice.account.application.command

import org.springframework.stereotype.Service

@Service
class CreateAccountService {
    fun create() {
        val region = System.getenv("AWS_REGION")
    }
}
