package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class ReactivateAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {
    fun reactivate(command: ReactivateAccountCommand) {
        val (accounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
            )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        account.reactivate()
        accountRepository.saveAccount(account)
        outboxRelay.processPending()
    }
}
