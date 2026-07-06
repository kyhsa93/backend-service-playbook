package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class CloseAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {

    fun close(command: CloseAccountCommand) {
        val account = accountRepository.findByAccountIdAndOwnerId(command.accountId, command.requesterId)
            ?: throw AccountNotFoundException(command.accountId)
        account.close()
        accountRepository.save(account)
        outboxRelay.processPending()
    }
}
