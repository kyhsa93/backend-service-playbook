package com.example.accountservice.account.domain

import com.example.accountservice.common.nowUtc
import java.time.LocalDateTime

class Account private constructor() {
    var accountId: String = ""
        private set

    // Stamped through the shared helper rather than LocalDateTime.now(), which would resolve
    // against the JVM's default zone — this mention of the bare call sits in a comment and must
    // not be counted as a violation.
    var createdAt: LocalDateTime = nowUtc()
        private set

    companion object {
        fun create(): Account =
            Account().apply {
                this.accountId = "acc-1"
                this.createdAt = nowUtc()
            }
    }
}
