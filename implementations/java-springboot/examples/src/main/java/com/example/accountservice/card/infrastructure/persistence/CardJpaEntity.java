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
 * card/domain/Card.java의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Card)는 이 클래스를 전혀 알지 못한다 — 변환은 CardMapper가 전담한다
 * (account/infrastructure/persistence/AccountJpaEntity와 동일한 구조, layer-architecture.md 참고).
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

    protected CardJpaEntity() {}

    CardJpaEntity(
            Long id,
            String cardId,
            String accountId,
            String ownerId,
            String brand,
            CardStatus status,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.cardId = cardId;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.brand = brand;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 기존 row(id 보존)에 도메인 Card의 최신 상태를 반영한다 — status 전이 저장에 사용. */
    void applyMutableState(CardStatus status) {
        this.status = status;
    }

    Long getId() { return id; }
    String getCardId() { return cardId; }
    String getAccountId() { return accountId; }
    String getOwnerId() { return ownerId; }
    String getBrand() { return brand; }
    CardStatus getStatus() { return status; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
