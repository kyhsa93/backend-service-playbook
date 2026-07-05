package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class SuspendAccountService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun suspend(command: SuspendAccountCommand) {
        val account = accountRepository.findByAccountIdAndOwnerId(command.accountId, command.requesterId)
            ?: throw AccountNotFoundException(command.accountId)
        account.suspend()
        accountRepository.save(account)
        account.pullDomainEvents().forEach(eventPublisher::publishEvent)
    }
}
