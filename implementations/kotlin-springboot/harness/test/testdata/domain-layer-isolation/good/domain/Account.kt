package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

class Account private constructor() {
    var accountId: String = ""
        private set

    companion object {
        fun create(): Account = Account().apply { this.accountId = generateId() }
    }
}
