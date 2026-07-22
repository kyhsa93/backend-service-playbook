package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.domain.CardStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The JPA-mapping-only counterpart to card/domain/Card.java. The Domain Aggregate (Card) has no
 * knowledge of this class at all — conversion is handled exclusively by CardMapper (the same
 * structure as account/infrastructure/persistence/AccountJpaEntity, see layer-architecture.md).
 */
@Entity
@Table(name = "card")
public class CardJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String cardId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // The field card/domain/Card.shouldSendStatement() uses to determine "has this month's
    // statement already been sent" — stores YearMonth as a "yyyy-MM" string (conversion is
    // handled exclusively by CardMapper; as with MoneyEmbeddable, the infrastructure layer
    // owns JDK value-type conversion).
    @Column(length = 7)
    private String lastStatementSentMonth;

    protected CardJpaEntity() {}

    CardJpaEntity(
            Long id,
            String cardId,
            String accountId,
            String ownerId,
            String brand,
            CardStatus status,
            LocalDateTime createdAt,
            String lastStatementSentMonth) {
        this.id = id;
        this.cardId = cardId;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.brand = brand;
        this.status = status;
        this.createdAt = createdAt;
        this.lastStatementSentMonth = lastStatementSentMonth;
    }

    /**
     * Applies the domain Card's latest state onto an existing row (preserving its id) — used for
     * status transitions and recording that a statement was sent.
     */
    void applyMutableState(CardStatus status, String lastStatementSentMonth) {
        this.status = status;
        this.lastStatementSentMonth = lastStatementSentMonth;
    }

    Long getId() {
        return id;
    }

    String getCardId() {
        return cardId;
    }

    String getAccountId() {
        return accountId;
    }

    String getOwnerId() {
        return ownerId;
    }

    String getBrand() {
        return brand;
    }

    CardStatus getStatus() {
        return status;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    String getLastStatementSentMonth() {
        return lastStatementSentMonth;
    }
}
