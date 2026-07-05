package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionJpaRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByCreatedAtDesc(String accountId, Pageable pageable);
    long countByAccountId(String accountId);
}
