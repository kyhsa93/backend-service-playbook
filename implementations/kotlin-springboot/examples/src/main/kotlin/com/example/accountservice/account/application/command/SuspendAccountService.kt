package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class SuspendAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {

    fun suspend(command: SuspendAccountCommand) {
        val account = accountRepository.findByAccountIdAndOwnerId(command.accountId, command.requesterId)
            ?: throw AccountNotFoundException(command.accountId)
        account.suspend()
        accountRepository.save(account)
        outboxRelay.processPending()
    }
}
