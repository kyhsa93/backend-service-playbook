package com.example.accountservice.auth.infrastructure.persistence

import com.example.accountservice.auth.domain.Credential

/**
 * Credential(순수 도메인) ↔ CredentialJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * CredentialRepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 오브젝트를 알지 못한다.
 */
internal object CredentialMapper {

    fun toDomain(entity: CredentialJpaEntity): Credential =
        Credential.reconstitute(
            credentialId = entity.credentialId,
            userId = entity.userId,
            passwordHash = entity.passwordHash,
            createdAt = entity.createdAt,
        )

    /** 신규 Credential을 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    fun toNewEntity(credential: Credential): CredentialJpaEntity =
        CredentialJpaEntity(
            id = null,
            credentialId = credential.credentialId,
            userId = credential.userId,
            passwordHash = credential.passwordHash,
            createdAt = credential.createdAt,
        )
}
