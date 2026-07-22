package com.example.accountservice.auth.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Credential Aggregate Root — represents login credentials (user ID + password hash).
 * The plaintext password is never stored anywhere in the domain/application layers — only passwordHash
 * is kept. Persistence mapping is handled entirely by infrastructure/persistence/CredentialJpaEntity +
 * CredentialMapper (the same domain/JPA separation structure as account/card).
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
        /** Issues a new Credential at sign-up. The password is received as an already-hashed value. */
        fun create(
            userId: String,
            passwordHash: String,
        ): Credential =
            Credential().apply {
                this.credentialId = generateId()
                this.userId = userId
                this.passwordHash = passwordHash
                this.createdAt = LocalDateTime.now()
            }

        /** Used by the Repository implementation to reconstitute a Credential from persisted data. */
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
