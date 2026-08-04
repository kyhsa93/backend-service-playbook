package com.example.accountservice.account.infrastructure.persistence

import java.time.LocalDateTime

class AccountJpaEntity(
    var accountId: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
