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
 * The JPA-mapping-only counterpart to card/domain/Card.kt.
 * The Domain Aggregate (Card) knows nothing of this class — conversion is handled exclusively by
 * CardMapper (the same structure as account/infrastructure/persistence/AccountJpaEntity).
 *
 * Properties are declared as `var` with defaults so the kotlin-jpa plugin can generate the no-arg
 * constructor Hibernate requires, and so CardMapper can directly overwrite the mutable fields of an
 * existing row (PK preserved) on update.
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
    @Column
    var lastStatementSentMonth: String? = null,
)
