package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.TransactionType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, Long> {
    List<TransactionJpaEntity> findByAccountIdOrderByCreatedAtDesc(
            String accountId, Pageable pageable);

    long countByAccountId(String accountId);

    // Level 2 Ledger 멱등성 체크(AccountRepository.hasTransactionWithReference) — referenceId만으로는
    // 결제완료(WITHDRAWAL)와 그 보상 크레딧(DEPOSIT)이 같은 paymentId를 공유해 서로를 "이미 처리됨"으로 잘못 판정하므로 type도 함께 확인한다.
    boolean existsByReferenceIdAndType(String referenceId, TransactionType type);
}
