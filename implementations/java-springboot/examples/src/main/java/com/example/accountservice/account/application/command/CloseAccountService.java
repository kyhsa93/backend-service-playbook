package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CloseAccountService {

    private final AccountRepository accountRepository;

    public void close(CloseAccountCommand command) {
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
        account.close();
        accountRepository.saveAccount(account);
    }
}
