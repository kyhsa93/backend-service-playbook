package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DepositService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun deposit(command: DepositCommand): TransactionResult {
        val account = accountRepository.findByAccountIdAndOwnerId(command.accountId, command.requesterId)
            ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.deposit(command.amount)
        accountRepository.save(account)
        account.pullDomainEvents().forEach(eventPublisher::publishEvent)
        return TransactionResult(
            transactionId = transaction.transactionId,
            accountId = transaction.accountId,
            type = transaction.type.name,
            amount = TransactionResult.MoneyResult(transaction.amount.amount, transaction.amount.currency),
            createdAt = transaction.createdAt,
        )
    }
}
