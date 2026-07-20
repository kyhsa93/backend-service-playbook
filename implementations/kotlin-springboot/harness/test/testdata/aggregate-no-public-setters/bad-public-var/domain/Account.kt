package com.example.accountservice.account.domain

class Account private constructor() {
    var accountId: String = ""
        private set

    // 공개 setter — 외부에서 account.status = "CLOSED" 처럼 도메인 메서드를 우회해 직접 대입 가능
    var status: String = "ACTIVE"

    companion object {
        fun create(): Account = Account().apply { this.accountId = "acc-1" }
    }

    fun suspend() {
        status = "SUSPENDED"
    }
}
