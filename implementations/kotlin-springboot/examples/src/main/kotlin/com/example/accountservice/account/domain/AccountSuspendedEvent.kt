package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountSuspendedEvent(val accountId: String, val suspendedAt: LocalDateTime)
