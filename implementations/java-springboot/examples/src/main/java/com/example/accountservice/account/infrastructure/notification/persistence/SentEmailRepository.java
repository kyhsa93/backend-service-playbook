package com.example.accountservice.account.infrastructure.notification.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentEmailRepository extends JpaRepository<SentEmail, Long> {
    Optional<SentEmail> findByAccountIdAndEventType(String accountId, String eventType);
}
