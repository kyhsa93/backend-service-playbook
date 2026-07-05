package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {

    private final AccountRepository accountRepository;

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        return new GetAccountResult(
                account.getAccountId(),
                account.getOwnerId(),
                new GetAccountResult.MoneyResult(account.getBalance().amount(), account.getBalance().currency()),
                account.getStatus().name(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
