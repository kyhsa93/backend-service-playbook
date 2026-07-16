package com.example.accountservice.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * auth/domain/Credential.java의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Credential)는 이 클래스를 전혀 알지 못한다 — 변환은 CredentialMapper가 전담한다
 * (account/infrastructure/persistence/AccountJpaEntity와 동일한 구조, layer-architecture.md 참고).
 */
@Entity
@Table(name = "credential")
public class CredentialJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String credentialId;

    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected CredentialJpaEntity() {}

    CredentialJpaEntity(
            Long id,
            String credentialId,
            String userId,
            String passwordHash,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.credentialId = credentialId;
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getCredentialId() { return credentialId; }
    String getUserId() { return userId; }
    String getPasswordHash() { return passwordHash; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
