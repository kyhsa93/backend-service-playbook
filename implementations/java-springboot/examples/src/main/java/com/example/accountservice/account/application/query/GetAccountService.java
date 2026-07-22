package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {

    private final AccountQuery accountQuery;

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account =
                accountQuery
                        .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "Account not found."));
        return new GetAccountResult(
                account.getAccountId(),
                account.getOwnerId(),
                account.getEmail(),
                new GetAccountResult.MoneyResult(
                        account.getBalance().amount(), account.getBalance().currency()),
                account.getStatus().name(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
