package com.example.accountservice.account.domain

import java.time.LocalDateTime

class Account private constructor() {
    var accountId: String = ""
        private set

    var status: String = "ACTIVE"
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun create(): Account = Account().apply { this.accountId = "acc-1" }
    }

    fun suspend() {
        status = "SUSPENDED"
    }
}
