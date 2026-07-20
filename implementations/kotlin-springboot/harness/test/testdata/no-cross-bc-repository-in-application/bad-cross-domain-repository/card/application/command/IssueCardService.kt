package com.example.accountservice.card.application.command

import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service

// 위반 — 다른 도메인(account)의 domain/AccountRepository를 application/에서 직접 import.
// Adapter(card/application/adapter/AccountAdapter + infrastructure/AccountAdapterImpl)를 거쳐야 함.
@Service
class IssueCardService(
    private val accountRepository: AccountRepository,
)
