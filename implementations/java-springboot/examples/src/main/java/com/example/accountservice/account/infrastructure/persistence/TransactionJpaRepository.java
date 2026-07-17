package com.example.accountservice.account.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, Long> {
    List<TransactionJpaEntity> findByAccountIdOrderByCreatedAtDesc(
            String accountId, Pageable pageable);

    long countByAccountId(String accountId);
}
