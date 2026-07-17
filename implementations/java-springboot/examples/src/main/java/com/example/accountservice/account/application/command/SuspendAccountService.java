package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SuspendAccountService {

    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public void suspend(SuspendAccountCommand command) {
        Account account =
                accountRepository
                        .findAccounts(
                                new AccountFindQuery(
                                        0, 1, command.accountId(), command.requesterId(), null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "계좌를 찾을 수 없습니다."));
        account.suspend();
        accountRepository.saveAccount(account);
        outboxRelay.processPending();
    }
}
