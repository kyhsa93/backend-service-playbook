package com.example.accountservice.card.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Card Aggregate Root — a pure domain object. It does not depend on any framework/ORM. Persistence
 * mapping is handled entirely by infrastructure/persistence/CardJpaEntity + CardMapper (the same
 * domain/JPA separation as account/domain/Account.java).
 */
public class Card {

    private String cardId;
    private String accountId;
    private String ownerId;
    private String brand;
    private CardStatus status;
    private LocalDateTime createdAt;
    // Level 2 Ledger field used to determine whether this month's card statement notice has
    // already been sent — see shouldSendStatement().
    private YearMonth lastStatementSentMonth;

    private Card() {}

    /**
     * Used by the Repository implementation to restore a Card from persisted data (a JPA entity,
     * etc). Unlike issue(), it does not create a new identifier/status — it reconstructs the saved
     * state as-is.
     */
    public static Card reconstitute(
            String cardId,
            String accountId,
            String ownerId,
            String brand,
            CardStatus status,
            LocalDateTime createdAt,
            YearMonth lastStatementSentMonth) {
        Card card = new Card();
        card.cardId = cardId;
        card.accountId = accountId;
        card.ownerId = ownerId;
        card.brand = brand;
        card.status = status;
        card.createdAt = createdAt;
        card.lastStatementSentMonth = lastStatementSentMonth;
        return card;
    }

    /**
     * The Card Aggregate cannot know whether the linked account is active — the Application layer
     * synchronously checks issuability (account status) via AccountAdapter (ACL) and only then
     * calls this factory.
     */
    public static Card issue(String accountId, String ownerId, String brand) {
        Card card = new Card();
        card.cardId = IdGenerator.generate();
        card.accountId = accountId;
        card.ownerId = ownerId;
        card.brand = brand;
        card.status = CardStatus.ACTIVE;
        card.createdAt = LocalDateTime.now();
        return card;
    }

    public void suspend() {
        if (this.status == CardStatus.CANCELLED) {
            throw new CardException(
                    CardException.ErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED,
                    "A cancelled card cannot be suspended.");
        }
        if (this.status == CardStatus.SUSPENDED) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ALREADY_SUSPENDED,
                    "The card is already suspended.");
        }
        this.status = CardStatus.SUSPENDED;
    }

    /**
     * Determines whether this card is a target that hasn't yet been sent this month's card
     * statement notice — this design inlines Level 2 Ledger idempotency into an Aggregate field
     * (see the 3 levels of idempotency in domain-events.md, for the same reason as
     * lastInterestPaidAt in account/domain/Account.java). Cancelled/suspended cards are excluded
     * from the target set — only ACTIVE cards receive the monthly notice.
     */
    public boolean shouldSendStatement(YearMonth month) {
        return this.status == CardStatus.ACTIVE && !month.equals(this.lastStatementSentMonth);
    }

    /**
     * Records that this month's notice has been sent — even if the batch job re-runs at-least-once
     * in the same month, it will not resend.
     */
    public void markStatementSent(YearMonth month) {
        this.lastStatementSentMonth = month;
    }

    public void cancel() {
        if (this.status == CardStatus.CANCELLED) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ALREADY_CANCELLED,
                    "The card is already cancelled.");
        }
        this.status = CardStatus.CANCELLED;
    }

    public String getCardId() {
        return cardId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getBrand() {
        return brand;
    }

    public CardStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public YearMonth getLastStatementSentMonth() {
        return lastStatementSentMonth;
    }
}
