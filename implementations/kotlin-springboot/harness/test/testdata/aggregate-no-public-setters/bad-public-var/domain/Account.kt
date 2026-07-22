package com.example.accountservice.account.domain

class Account private constructor() {
    var accountId: String = ""
        private set

    // Public setter — allows external code to assign directly, bypassing domain methods, e.g. account.status = "CLOSED"
    var status: String = "ACTIVE"

    companion object {
        fun create(): Account = Account().apply { this.accountId = "acc-1" }
    }

    fun suspend() {
        status = "SUSPENDED"
    }
}
