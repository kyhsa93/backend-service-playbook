package com.example.accountservice.auth.infrastructure.persistence

import com.example.accountservice.auth.domain.Credential

/**
 * The object dedicated to converting between Credential (pure domain) and CredentialJpaEntity (JPA
 * mapping). Used only inside CredentialRepositoryImpl — the Domain/Application layers have no knowledge
 * of this object.
 */
internal object CredentialMapper {
    fun toDomain(entity: CredentialJpaEntity): Credential =
        Credential.reconstitute(
            credentialId = entity.credentialId,
            userId = entity.userId,
            passwordHash = entity.passwordHash,
            createdAt = entity.createdAt,
        )

    /** Creates a new entity (no PK, an insert target) for a new Credential. */
    fun toNewEntity(credential: Credential): CredentialJpaEntity =
        CredentialJpaEntity(
            id = null,
            credentialId = credential.credentialId,
            userId = credential.userId,
            passwordHash = credential.passwordHash,
            createdAt = credential.createdAt,
        )
}
