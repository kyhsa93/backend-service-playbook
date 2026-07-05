package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CreateAccountService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.save(account);
        account.pullDomainEvents().forEach(eventPublisher::publishEvent);
        return new CreateAccountResult(
                account.getAccountId(),
                account.getOwnerId(),
                account.getEmail(),
                new CreateAccountResult.MoneyResult(account.getBalance().amount(), account.getBalance().currency()),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}
