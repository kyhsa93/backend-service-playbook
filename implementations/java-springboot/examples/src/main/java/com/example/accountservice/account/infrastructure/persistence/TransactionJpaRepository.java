package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, Long> {

    // The Level 2 Ledger idempotency check (AccountRepository.hasTransactionWithReference) —
    // referenceId
    // alone is insufficient because the payment-completed transaction (WITHDRAWAL) and its
    // compensating
    // credit (DEPOSIT) share the same paymentId and would incorrectly judge each other as "already
    // processed," so type is checked together with it.
    boolean existsByReferenceIdAndType(String referenceId, TransactionType type);
}
