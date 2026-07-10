package com.example.accountservice.notification.infrastructure.persistence;

import com.example.accountservice.common.IdGenerator;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sent_email")
public class SentEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sentEmailId;

    @Column(nullable = false)
    private String accountId;

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

    protected SentEmail() {}

    public static SentEmail create(String accountId, String eventType, String recipient, String subject, String sesMessageId) {
        SentEmail sentEmail = new SentEmail();
        sentEmail.sentEmailId = IdGenerator.generate();
        sentEmail.accountId = accountId;
        sentEmail.eventType = eventType;
        sentEmail.recipient = recipient;
        sentEmail.subject = subject;
        sentEmail.sesMessageId = sesMessageId;
        sentEmail.sentAt = LocalDateTime.now();
        return sentEmail;
    }

    public String getSentEmailId() { return sentEmailId; }
    public String getAccountId() { return accountId; }
    public String getEventType() { return eventType; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getSesMessageId() { return sesMessageId; }
    public LocalDateTime getSentAt() { return sentAt; }
}
