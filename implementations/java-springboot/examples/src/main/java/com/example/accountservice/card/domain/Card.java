package com.example.accountservice.card.domain;

import com.example.accountservice.common.IdGenerator;

import java.time.LocalDateTime;

/**
 * Card Aggregate Root — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다.
 * 영속성 매핑은 infrastructure/persistence/CardJpaEntity + CardMapper가 전담한다
 * (account/domain/Account.java와 동일한 domain/JPA 분리 구조).
 */
public class Card {

    private String cardId;
    private String accountId;
    private String ownerId;
    private String brand;
    private CardStatus status;
    private LocalDateTime createdAt;

    private Card() {}

    /**
     * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Card를 복원할 때 사용한다.
     * issue()와 달리 새 식별자·상태를 만들지 않고 저장된 상태를 그대로 재구성한다.
     */
    public static Card reconstitute(
            String cardId,
            String accountId,
            String ownerId,
            String brand,
            CardStatus status,
            LocalDateTime createdAt
    ) {
        Card card = new Card();
        card.cardId = cardId;
        card.accountId = accountId;
        card.ownerId = ownerId;
        card.brand = brand;
        card.status = status;
        card.createdAt = createdAt;
        return card;
    }

    /**
     * 연결 계좌의 활성 여부는 Card Aggregate가 알 수 없다 — 발급 가능 여부(계좌 상태)는
     * Application 레이어가 AccountAdapter(ACL)로 동기 조회해 판단한 뒤 이 팩토리를 호출한다.
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
            throw new CardException(CardException.ErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED, "해지된 카드는 정지할 수 없습니다.");
        }
        if (this.status == CardStatus.SUSPENDED) {
            throw new CardException(CardException.ErrorCode.CARD_ALREADY_SUSPENDED, "이미 정지된 카드입니다.");
        }
        this.status = CardStatus.SUSPENDED;
    }

    public void cancel() {
        if (this.status == CardStatus.CANCELLED) {
            throw new CardException(CardException.ErrorCode.CARD_ALREADY_CANCELLED, "이미 해지된 카드입니다.");
        }
        this.status = CardStatus.CANCELLED;
    }

    public String getCardId() { return cardId; }
    public String getAccountId() { return accountId; }
    public String getOwnerId() { return ownerId; }
    public String getBrand() { return brand; }
    public CardStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
