package com.example.accountservice.account.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {
    Optional<AccountJpaEntity> findByAccountId(String accountId);

    Optional<AccountJpaEntity> findByAccountIdAndDeletedAtIsNull(String accountId);
}
