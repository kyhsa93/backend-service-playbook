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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The JPA-mapping counterpart of account/domain/Account.java. The Domain Aggregate (Account) has no
 * knowledge of this class at all — the conversion is handled entirely by AccountMapper (see
 * layer-architecture.md).
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

    @Embedded private MoneyEmbeddable balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column private LocalDateTime deletedAt;

    // The field account/domain/Account.payInterest() uses to determine "has interest already been
    // paid today" (Level 1 idempotency) — see account/domain/Account.java.
    @Column private LocalDate lastInterestPaidAt;

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
            LocalDateTime deletedAt,
            LocalDate lastInterestPaidAt) {
        this.id = id;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.email = email;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.lastInterestPaidAt = lastInterestPaidAt;
    }

    /**
     * Applies the domain Account's latest state onto the existing row (id preserved) — used for
     * update saves.
     */
    void applyMutableState(
            String email,
            MoneyEmbeddable balance,
            AccountStatus status,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt,
            LocalDate lastInterestPaidAt) {
        this.email = email;
        this.balance = balance;
        this.status = status;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.lastInterestPaidAt = lastInterestPaidAt;
    }

    Long getId() {
        return id;
    }

    String getAccountId() {
        return accountId;
    }

    String getOwnerId() {
        return ownerId;
    }

    String getEmail() {
        return email;
    }

    MoneyEmbeddable getBalance() {
        return balance;
    }

    AccountStatus getStatus() {
        return status;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    LocalDate getLastInterestPaidAt() {
        return lastInterestPaidAt;
    }
}
