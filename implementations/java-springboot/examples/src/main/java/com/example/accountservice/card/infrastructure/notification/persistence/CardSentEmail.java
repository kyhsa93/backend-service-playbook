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
 * account/infrastructure/notification/persistence/SentEmail과 동일한 구조의 발송 이력 — Card BC가 자신의
 * NotificationService 구현체를 따로 갖는 것과 마찬가지로(domain-service.md "배치 원칙"), 발송 이력 테이블도 Account BC의
 * sent_email을 공유하지 않고 Card BC 소유로 별도로 둔다.
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
