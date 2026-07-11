package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * account/domain/Account.java의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Account)는 이 클래스를 전혀 알지 못한다 — 변환은 AccountMapper가 전담한다(layer-architecture.md 참고).
 */
@Entity
@Table(name = "accounts")
public class AccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String email;

    @Embedded
    private MoneyEmbeddable balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;

    protected AccountJpaEntity() {}

    AccountJpaEntity(
            Long id,
            String accountId,
            String ownerId,
            String email,
            MoneyEmbeddable balance,
            AccountStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {
        this.id = id;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.email = email;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    /** 기존 row(id 보존)에 도메인 Account의 최신 상태를 반영한다 — update 저장에 사용. */
    void applyMutableState(
            String email,
            MoneyEmbeddable balance,
            AccountStatus status,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {
        this.email = email;
        this.balance = balance;
        this.status = status;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    Long getId() { return id; }
    String getAccountId() { return accountId; }
    String getOwnerId() { return ownerId; }
    String getEmail() { return email; }
    MoneyEmbeddable getBalance() { return balance; }
    AccountStatus getStatus() { return status; }
    LocalDateTime getCreatedAt() { return createdAt; }
    LocalDateTime getUpdatedAt() { return updatedAt; }
    LocalDateTime getDeletedAt() { return deletedAt; }
}
