package com.example.accountservice.account.infrastructure.persistence

import java.time.LocalDateTime
import java.time.ZoneOffset

// Converts inline instead of going through a shared helper — still a UTC reading, so the rule
// accepts it (an external project need not have adopted a common clock package).
class AccountJpaEntity(
    var accountId: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
)
