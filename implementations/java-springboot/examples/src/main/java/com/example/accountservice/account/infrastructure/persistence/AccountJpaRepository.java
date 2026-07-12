package com.example.accountservice.account.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {
    Optional<AccountJpaEntity> findByAccountId(String accountId);
    Optional<AccountJpaEntity> findByAccountIdAndDeletedAtIsNull(String accountId);
}
