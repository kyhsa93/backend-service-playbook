package com.example.accountservice.auth.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * Credential Aggregate Root — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 평문 비밀번호는 domain/application 어디에도
 * 보관하지 않는다 — passwordHash만 갖는다. 영속성 매핑은 infrastructure/persistence/CredentialJpaEntity +
 * CredentialMapper가 전담한다 (account/domain/Account.java와 동일한 domain/JPA 분리 구조).
 */
public class Credential {

    private String credentialId;
    private String userId;
    private String passwordHash;
    private LocalDateTime createdAt;

    private Credential() {}

    /**
     * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Credential을 복원할 때 사용한다. create()와 달리 새 식별자를 만들지 않고 저장된
     * 상태를 그대로 재구성한다.
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
     * 신규 가입. 비밀번호 해싱은 Application 레이어가 PasswordHasher(Technical Service)로 미리 수행한 뒤 그
     * 결과(passwordHash)만 이 팩토리에 넘긴다 — Domain은 해싱 알고리즘을 모른다.
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
