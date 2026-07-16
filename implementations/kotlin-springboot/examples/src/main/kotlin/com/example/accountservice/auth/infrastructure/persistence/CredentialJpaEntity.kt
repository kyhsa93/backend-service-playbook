package com.example.accountservice.auth.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * auth/domain/Credential.kt의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Credential)는 이 클래스를 전혀 알지 못한다 — 변환은 CredentialMapper가 전담한다
 * (card/infrastructure/persistence/CardJpaEntity와 동일한 구조).
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
