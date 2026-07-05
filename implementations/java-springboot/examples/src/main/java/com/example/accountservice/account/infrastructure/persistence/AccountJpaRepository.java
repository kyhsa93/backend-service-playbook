package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountIdAndOwnerIdAndDeletedAtIsNull(String accountId, String ownerId);
}
