package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.AccountStatus
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The JPA-mapping counterpart of account/domain/Account.kt.
 * The domain Aggregate (Account) has no awareness of this class whatsoever — conversion is handled
 * exclusively by AccountMapper (see layer-architecture.md).
 *
 * Properties are declared as `var` with default values so the kotlin-jpa plugin can generate the no-arg
 * constructor Hibernate requires, and so AccountMapper can overwrite the mutable fields of an existing
 * row (PK preserved) in place on update.
 */
@Entity
@Table(name = "accounts")
class AccountJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var accountId: String = "",
    @Column(nullable = false)
    var ownerId: String = "",
    @Column(nullable = false)
    var email: String = "",
    @Embedded
    var balance: MoneyEmbeddable = MoneyEmbeddable(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AccountStatus = AccountStatus.ACTIVE,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column
    var deletedAt: LocalDateTime? = null,
    @Column
    var lastInterestPaidAt: LocalDate? = null,
)
