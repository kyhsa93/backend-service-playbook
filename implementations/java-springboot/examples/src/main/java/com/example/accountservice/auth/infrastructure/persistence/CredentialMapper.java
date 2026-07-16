package com.example.accountservice.auth.infrastructure.persistence;

import com.example.accountservice.auth.domain.Credential;

/**
 * Credential(순수 도메인) ↔ CredentialJpaEntity(JPA 매핑) 변환 전담 클래스.
 * CredentialRepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 클래스를 알지 못한다.
 */
final class CredentialMapper {

    private CredentialMapper() {}

    static Credential toDomain(CredentialJpaEntity entity) {
        return Credential.reconstitute(
                entity.getCredentialId(),
                entity.getUserId(),
                entity.getPasswordHash(),
                entity.getCreatedAt()
        );
    }

    /** 신규 Credential을 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. Credential은 생성 후 수정되지 않는다. */
    static CredentialJpaEntity toNewEntity(Credential credential) {
        return new CredentialJpaEntity(
                null,
                credential.getCredentialId(),
                credential.getUserId(),
                credential.getPasswordHash(),
                credential.getCreatedAt()
        );
    }
}
