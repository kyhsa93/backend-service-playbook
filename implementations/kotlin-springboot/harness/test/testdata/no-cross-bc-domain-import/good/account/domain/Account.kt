package com.example.accountservice.account.domain

class Account private constructor() {
    var accountId: String = ""
        private set

    companion object {
        fun create(): Account = Account().apply { this.accountId = "acc-1" }
    }
}
