package com.example.accountservice.auth.infrastructure.persistence;

import com.example.accountservice.auth.domain.Credential;

/**
 * The class dedicated to converting between Credential (pure domain) and CredentialJpaEntity (JPA
 * mapping). It is used only inside CredentialRepositoryImpl — the Domain/Application layers have no
 * knowledge of this class.
 */
final class CredentialMapper {

    private CredentialMapper() {}

    static Credential toDomain(CredentialJpaEntity entity) {
        return Credential.reconstitute(
                entity.getCredentialId(),
                entity.getUserId(),
                entity.getPasswordHash(),
                entity.getCreatedAt());
    }

    /**
     * Creates a new entity (no PK, to be inserted) for a new Credential. A Credential is never
     * modified after creation.
     */
    static CredentialJpaEntity toNewEntity(Credential credential) {
        return new CredentialJpaEntity(
                null,
                credential.getCredentialId(),
                credential.getUserId(),
                credential.getPasswordHash(),
                credential.getCreatedAt());
    }
}
