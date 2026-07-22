package com.example.accountservice.auth.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA mapping counterpart to auth/domain/Credential.kt.
 * The Domain Aggregate (Credential) has no knowledge of this class whatsoever — conversion is handled
 * entirely by CredentialMapper (the same structure as card/infrastructure/persistence/CardJpaEntity).
 */
@Entity
@Table(name = "credentials")
class CredentialJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var credentialId: String = "",
    @Column(nullable = false, unique = true)
    var userId: String = "",
    @Column(nullable = false)
    var passwordHash: String = "",
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
