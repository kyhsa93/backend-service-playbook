package com.example.accountservice.card.infrastructure.notification.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardSentEmailRepository extends JpaRepository<CardSentEmail, Long> {
    Optional<CardSentEmail> findByCardIdAndEventType(String cardId, String eventType);
}
