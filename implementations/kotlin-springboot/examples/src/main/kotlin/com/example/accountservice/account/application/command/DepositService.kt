package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class DepositService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {

    fun deposit(command: DepositCommand): TransactionResult {
        val account = accountRepository.findByAccountIdAndOwnerId(command.accountId, command.requesterId)
            ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.deposit(command.amount)
        accountRepository.save(account)
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
