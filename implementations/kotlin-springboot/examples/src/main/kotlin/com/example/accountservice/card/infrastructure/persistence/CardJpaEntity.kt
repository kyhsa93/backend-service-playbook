package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.domain.CardStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * card/domain/Card.kt의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Card)는 이 클래스를 전혀 알지 못한다 — 변환은 CardMapper가 전담한다
 * (account/infrastructure/persistence/AccountJpaEntity와 동일한 구조).
 *
 * 프로퍼티를 `var` + 기본값으로 선언해 Hibernate가 요구하는 기본 생성자를 kotlin-jpa 플러그인이
 * 생성하게 하고, 갱신 시 CardMapper가 기존 행(PK 보존)의 가변 필드를 그대로 덮어쓸 수 있게 한다.
 */
@Entity
@Table(name = "cards")
class CardJpaEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var cardId: String = "",

    @Column(nullable = false)
    var accountId: String = "",

    @Column(nullable = false)
    var ownerId: String = "",

    @Column(nullable = false)
    var brand: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CardStatus = CardStatus.ACTIVE,

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
