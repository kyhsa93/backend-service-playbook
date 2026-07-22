package com.example.accountservice.auth.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * The Credential Aggregate Root — a pure domain object. It depends on no framework/ORM. The
 * plaintext password is not held anywhere in domain/application — only passwordHash is kept. The
 * persistence mapping is handled entirely by infrastructure/persistence/CredentialJpaEntity +
 * CredentialMapper (the same domain/JPA separation structure as account/domain/Account.java).
 */
public class Credential {

    private String credentialId;
    private String userId;
    private String passwordHash;
    private LocalDateTime createdAt;

    private Credential() {}

    /**
     * Used by a Repository implementation to reconstitute a Credential from persisted data (a JPA
     * entity, etc.). Unlike create(), it does not generate a new identifier — it reconstructs the
     * stored state as-is.
     */
    public static Credential reconstitute(
            String credentialId, String userId, String passwordHash, LocalDateTime createdAt) {
        Credential credential = new Credential();
        credential.credentialId = credentialId;
        credential.userId = userId;
        credential.passwordHash = passwordHash;
        credential.createdAt = createdAt;
        return credential;
    }

    /**
     * A new sign-up. Password hashing is performed beforehand by the Application layer via
     * PasswordHasher (a Technical Service), and only the result (passwordHash) is passed to this
     * factory — the Domain does not know the hashing algorithm.
     */
    public static Credential create(String userId, String passwordHash) {
        Credential credential = new Credential();
        credential.credentialId = IdGenerator.generate();
        credential.userId = userId;
        credential.passwordHash = passwordHash;
        credential.createdAt = LocalDateTime.now();
        return credential;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
