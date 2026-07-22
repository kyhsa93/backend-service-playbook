package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The "delete" (data lifecycle management) use case, distinct from "close" (a state transition).
 * Only a closed (CLOSED) account can be soft-deleted — invariant validation is Account.delete()'s
 * responsibility (see persistence.md).
 */
@Service
@RequiredArgsConstructor
public class DeleteAccountService {

    private final AccountRepository accountRepository;

    public void delete(DeleteAccountCommand command) {
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
        accountRepository.deleteAccount(command.accountId());
    }
}
