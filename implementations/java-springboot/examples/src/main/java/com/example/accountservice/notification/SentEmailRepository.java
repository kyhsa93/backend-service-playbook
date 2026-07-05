package com.example.accountservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SentEmailRepository extends JpaRepository<SentEmail, Long> {
    Optional<SentEmail> findByAccountIdAndEventType(String accountId, String eventType);
}
