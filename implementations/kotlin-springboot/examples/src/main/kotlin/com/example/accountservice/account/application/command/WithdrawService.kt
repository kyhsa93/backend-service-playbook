package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class WithdrawService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {

    fun withdraw(command: WithdrawCommand): TransactionResult {
        val (accounts, _) = accountRepository.findAccounts(
            AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
        )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.withdraw(command.amount)
        accountRepository.saveAccount(account)
        outboxRelay.processPending()
        return TransactionResult(
            transactionId = transaction.transactionId,
            accountId = transaction.accountId,
            type = transaction.type.name,
            amount = TransactionResult.MoneyResult(transaction.amount.amount, transaction.amount.currency),
            createdAt = transaction.createdAt,
        )
    }
}
