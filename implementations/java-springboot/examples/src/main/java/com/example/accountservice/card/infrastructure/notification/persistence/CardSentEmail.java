package com.example.accountservice.card.infrastructure.notification.persistence;

import com.example.accountservice.common.IdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A send-history record with the same structure as
 * account/infrastructure/notification/persistence/SentEmail — just as the Card BC has its own
 * separate NotificationService implementation (domain-service.md "placement principle"), the
 * send-history table is also kept separately, owned by the Card BC, rather than sharing the Account
 * BC's sent_email table.
 */
@Entity
@Table(name = "card_sent_email")
public class CardSentEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sentEmailId;

    @Column(nullable = false)
    private String cardId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String sesMessageId;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    protected CardSentEmail() {}

    public static CardSentEmail create(
            String cardId,
            String eventType,
            String recipient,
            String subject,
            String sesMessageId) {
        CardSentEmail sentEmail = new CardSentEmail();
        sentEmail.sentEmailId = IdGenerator.generate();
        sentEmail.cardId = cardId;
        sentEmail.eventType = eventType;
        sentEmail.recipient = recipient;
        sentEmail.subject = subject;
        sentEmail.sesMessageId = sesMessageId;
        sentEmail.sentAt = LocalDateTime.now();
        return sentEmail;
    }

    public String getSentEmailId() {
        return sentEmailId;
    }

    public String getCardId() {
        return cardId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getSesMessageId() {
        return sesMessageId;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
