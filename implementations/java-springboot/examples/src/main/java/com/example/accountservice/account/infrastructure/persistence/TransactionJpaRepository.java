package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.TransactionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, Long> {

    // The Level 2 Ledger idempotency check (AccountRepository.hasTransactionWithReference) —
    // referenceId
    // alone is insufficient because the payment-completed transaction (WITHDRAWAL) and its
    // compensating
    // credit (DEPOSIT) share the same paymentId and would incorrectly judge each other as "already
    // processed," so type is checked together with it.
    boolean existsByReferenceIdAndType(String referenceId, TransactionType type);

    // Backs TransactionRepositoryImpl's find→modify-via-domain-method→save cycle (see
    // account/domain/TransactionRepository.java) — CategorizeTransactionEventHandler's only lookup
    // path.
    Optional<TransactionJpaEntity> findByTransactionId(String transactionId);

    // Backs TransactionRepositoryImpl#findRecentWithdrawalAmounts — the training data for
    // AnomalyDetectionService. transactionId (not id) is excluded since that's the domain-facing
    // identifier DetectWithdrawalAnomalyEventHandler receives on the event.
    List<TransactionJpaEntity> findByAccountIdAndTypeAndTransactionIdNotOrderByCreatedAtDesc(
            String accountId, TransactionType type, String transactionId, Pageable pageable);
}
