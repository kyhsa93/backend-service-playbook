package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionRepository;
import com.example.accountservice.account.domain.TransactionType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate from {@link AccountRepositoryImpl} — see account/domain/TransactionRepository.java for
 * why this find→modify→save cycle needs its own Repository rather than living on AccountRepository
 * (which only ever inserts Transaction rows in bulk as a side effect of saveAccount).
 */
@Repository
@RequiredArgsConstructor
public class TransactionRepositoryImpl implements TransactionRepository {

    private final TransactionJpaRepository jpaRepository;

    @Override
    public Transaction findTransaction(String transactionId) {
        return jpaRepository
                .findByTransactionId(transactionId)
                .map(TransactionMapper::toDomain)
                .orElse(null);
    }

    @Override
    @Transactional
    public void saveTransaction(Transaction transaction) {
        TransactionJpaEntity entity =
                jpaRepository
                        .findByTransactionId(transaction.getTransactionId())
                        .map(existing -> TransactionMapper.updateEntity(existing, transaction))
                        .orElseGet(() -> TransactionMapper.toNewEntity(transaction));
        jpaRepository.save(entity);
    }

    @Override
    public List<Long> findRecentWithdrawalAmounts(
            String accountId, String excludeTransactionId, int limit) {
        return jpaRepository
                .findByAccountIdAndTypeAndTransactionIdNotOrderByCreatedAtDesc(
                        accountId,
                        TransactionType.WITHDRAWAL,
                        excludeTransactionId,
                        PageRequest.of(0, limit))
                .stream()
                .map(entity -> entity.getAmount().amount())
                .toList();
    }
}
