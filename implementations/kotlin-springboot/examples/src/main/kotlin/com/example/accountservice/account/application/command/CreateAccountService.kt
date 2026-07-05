package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.save(account)
        account.pullDomainEvents().forEach(eventPublisher::publishEvent)
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
