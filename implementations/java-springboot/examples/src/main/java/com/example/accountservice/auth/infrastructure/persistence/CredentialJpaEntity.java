package com.example.accountservice.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The JPA-mapping counterpart of auth/domain/Credential.java. The Domain Aggregate (Credential) has
 * no knowledge of this class at all — the conversion is handled entirely by CredentialMapper (the
 * same structure as account/infrastructure/persistence/AccountJpaEntity, see
 * layer-architecture.md).
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
            LocalDateTime createdAt) {
        this.id = id;
        this.credentialId = credentialId;
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getCredentialId() {
        return credentialId;
    }

    String getUserId() {
        return userId;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
