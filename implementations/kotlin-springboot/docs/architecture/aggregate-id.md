# Aggregate ID Generation — Kotlin Spring Boot

> For the framework-agnostic principles, see [root aggregate-id.md](../../../../docs/architecture/aggregate-id.md).

### Principle summary

- **Where the ID is generated**: inside the Domain layer, in the Aggregate's `companion object` factory function
- **Who generates it**: the server. Never trust an ID sent by the client
- **Type**: `String`
- **Format**: a UUID v4 with **hyphens removed, as a 32-character hex string**

```kotlin
'550e8400e29b41d4a716446655440000'      // correct — 32 characters, no hyphens
'550e8400-e29b-41d4-a716-446655440000'  // incorrect — contains hyphens
1, 2, 3                                  // incorrect — auto-increment number
```

---

## `generateId()`

Every ID issued by `Account.create()`, `Transaction.create()`, and `notification/.../SentEmail.create()` uses the `generateId()` below (32-character hex, hyphens removed).

---

## ID generation util — correct implementation

```kotlin
// common/GenerateId.kt
package com.example.accountservice.common

import java.util.UUID

fun generateId(): String = UUID.randomUUID().toString().replace("-", "")
```

In Kotlin this can be declared as a top-level function, so no separate utility class is needed. There's no need to wrap it in an `object GenerateId { ... }` singleton either — a single file is already a module.

---

## Using it in an Aggregate — correct pattern

```kotlin
package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "accounts")
class Account protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var accountId: String = ""
        private set

    // ...

    companion object {
        fun create(ownerId: String, currency: String, email: String): Account =
            Account().apply {
                this.accountId = generateId()   // 32-character hex, hyphens removed
                this.ownerId = ownerId
                this.email = email
                this.balance = Money(0, currency)
                this.status = AccountStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
                this.updatedAt = this.createdAt
                this.domainEvents += AccountCreatedEvent(this.accountId, ownerId, email, currency, this.createdAt)
            }
    }
}
```

**Distinguishing new creation from DB restoration** is naturally solved by the two-stage constructor pattern typical of JPA Entities:

- **New creation**: `Account.create(...)` — the `companion object` factory assigns a new ID via `generateId()`
- **DB restoration**: JPA (Hibernate) creates a proxy via `protected constructor()`, then fills the `@Id` field via reflection. Application code is not involved

Separating `id: Long?` (JPA surrogate key, `@GeneratedValue`) from `accountId: String` (domain identifier) also aligns with the root principle: **the DB-generated auto-increment value is used only as an internal JPA identifier, and the identifier exposed to the domain/outside world is always the application-generated `accountId`.** `AccountController`, `AccountRepository`, and every Command/Result reference only `accountId`; `id: Long` is never exposed anywhere.

---

## Handling IDs in the Repository implementation

The Repository persists the `accountId` the Aggregate already has, as-is. It never issues a new domain ID from the DB.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt
override fun save(account: Account) {
    jpaRepository.save(account)   // saved with account.accountId kept unchanged
}
```

---

## Child Entity IDs

Child Entities inside an Aggregate (`Transaction`) also use the same `generateId()`-based 32-character hex string.

```kotlin
// domain/Transaction.kt
companion object {
    fun create(accountId: String, type: TransactionType, amount: Money): Transaction =
        Transaction().apply {
            this.transactionId = generateId()   // same ID generation rule as Account
            this.accountId = accountId
            this.type = type
            this.amount = amount
            this.createdAt = LocalDateTime.now()
        }
}
```

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Aggregate factory function pattern
- [repository-pattern.md](repository-pattern.md) — saving Aggregates in the Repository
- harness `aggregate-id-format` rule (`../../harness/README.md`) — mechanically verifies that `GenerateId.kt` doesn't return `UUID.randomUUID().toString()` as-is without removing hyphens
