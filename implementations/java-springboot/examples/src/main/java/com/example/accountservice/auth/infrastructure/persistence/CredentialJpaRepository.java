package com.example.accountservice.auth.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialJpaRepository extends JpaRepository<CredentialJpaEntity, Long> {
    Optional<CredentialJpaEntity> findByUserId(String userId);
}
