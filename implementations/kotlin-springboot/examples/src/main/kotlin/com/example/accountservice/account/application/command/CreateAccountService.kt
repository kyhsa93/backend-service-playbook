package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {

    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)
        outboxRelay.processPending()
        return CreateAccountResult(
            accountId = account.accountId,
            ownerId = account.ownerId,
            email = account.email,
            balance = CreateAccountResult.MoneyResult(account.balance.amount, account.balance.currency),
            status = account.status.name,
            createdAt = account.createdAt,
        )
    }
}
