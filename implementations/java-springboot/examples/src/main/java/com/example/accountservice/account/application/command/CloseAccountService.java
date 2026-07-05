package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CloseAccountService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public void close(CloseAccountCommand command) {
        Account account = accountRepository.findByAccountIdAndOwnerId(command.accountId(), command.requesterId())
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        account.close();
        accountRepository.save(account);
        account.pullDomainEvents().forEach(eventPublisher::publishEvent);
    }
}
