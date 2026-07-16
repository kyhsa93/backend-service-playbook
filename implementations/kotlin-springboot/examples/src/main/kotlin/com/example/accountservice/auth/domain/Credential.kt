package com.example.accountservice.auth.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Credential Aggregate Root — 로그인 자격증명(아이디+비밀번호 해시)을 표현한다.
 * 평문 비밀번호는 domain/application 어디에도 보관하지 않는다 — passwordHash만 갖는다.
 * 영속성 매핑은 infrastructure/persistence/CredentialJpaEntity + CredentialMapper가 전담한다
 * (account/card와 동일한 domain/JPA 분리 구조).
 */
class Credential private constructor() {

    var credentialId: String = ""
        private set

    var userId: String = ""
        private set

    var passwordHash: String = ""
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        /** 회원가입 시 새 Credential을 발급한다. 비밀번호는 이미 해싱된 값을 받는다. */
        fun create(userId: String, passwordHash: String): Credential =
            Credential().apply {
                this.credentialId = generateId()
                this.userId = userId
                this.passwordHash = passwordHash
                this.createdAt = LocalDateTime.now()
            }

        /** Repository 구현체가 영속 데이터로부터 Credential을 복원할 때 사용한다. */
        fun reconstitute(
            credentialId: String,
            userId: String,
            passwordHash: String,
            createdAt: LocalDateTime,
        ): Credential =
            Credential().apply {
                this.credentialId = credentialId
                this.userId = userId
                this.passwordHash = passwordHash
                this.createdAt = createdAt
            }
    }
}
