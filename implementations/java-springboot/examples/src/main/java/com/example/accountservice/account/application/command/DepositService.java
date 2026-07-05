package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DepositService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TransactionResult deposit(DepositCommand command) {
        Account account = accountRepository.findByAccountIdAndOwnerId(command.accountId(), command.requesterId())
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        Transaction transaction = account.deposit(command.amount());
        accountRepository.save(account);
        account.pullDomainEvents().forEach(eventPublisher::publishEvent);
        return new TransactionResult(
                transaction.getTransactionId(),
                transaction.getAccountId(),
                transaction.getType().name(),
                new TransactionResult.MoneyResult(transaction.getAmount().amount(), transaction.getAmount().currency()),
                transaction.getCreatedAt()
        );
    }
}
