package com.example.accountservice.account.domain

import org.slf4j.LoggerFactory

class Account private constructor() {
    var accountId: String = ""
        private set

    companion object {
        private val logger = LoggerFactory.getLogger(Account::class.java)

        fun create(): Account = Account().apply { this.accountId = "acc-1" }
    }
}
