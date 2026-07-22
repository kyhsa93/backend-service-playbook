package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReactivateAccountService {

    private final AccountRepository accountRepository;

    public void reactivate(ReactivateAccountCommand command) {
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
                                                "Account not found."));
        account.reactivate();
        accountRepository.saveAccount(account);
    }
}
