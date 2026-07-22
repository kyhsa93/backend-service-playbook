package com.example.accountservice.card.application.command

import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service

// Violation — directly imports another domain's(account) domain/AccountRepository inside application/.
// Must go through an Adapter(card/application/adapter/AccountAdapter + infrastructure/AccountAdapterImpl).
@Service
class IssueCardService(
    private val accountRepository: AccountRepository,
)
