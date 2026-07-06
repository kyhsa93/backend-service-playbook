# Aggregate ID 생성 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root aggregate-id.md](../../../../docs/architecture/aggregate-id.md) 참조.

### 원칙 요약

- **ID 생성 위치**: Domain 레이어, Aggregate의 `companion object` 팩토리 함수 내부
- **생성 주체**: 서버. 클라이언트가 보낸 ID를 신뢰하지 않는다
- **타입**: `String`
- **형식**: UUID v4에서 **하이픈을 제거한 32자리 hex 문자열**

```kotlin
'550e8400e29b41d4a716446655440000'      // 올바른 방식 — 32자리, 하이픈 없음
'550e8400-e29b-41d4-a716-446655440000'  // 잘못된 방식 — 하이픈 포함
1, 2, 3                                  // 잘못된 방식 — auto-increment 숫자
```

---

## 알려진 갭 — 현재 예제 코드는 이 규칙을 지키지 않는다

`examples/src/main/kotlin/com/example/accountservice/account/domain/Account.kt`의 `companion object.create()`:

```kotlin
companion object {
    fun create(ownerId: String, currency: String, email: String): Account =
        Account().apply {
            this.accountId = UUID.randomUUID().toString()   // ← 하이픈 포함 — root 규칙 위반
            ...
        }
}
```

`Transaction.kt`의 `Transaction.create()`도 동일하게 `UUID.randomUUID().toString()`을 그대로 사용한다. `toString()`은 `550e8400-e29b-41d4-a716-446655440000` 형태의 하이픈 포함 문자열을 반환하므로, root가 명시적으로 금지하는 형식이다. **이 문서는 올바른 규칙을 아래에 정의하며, `examples/`가 이를 따르도록 고치는 것은 후속 작업으로 남긴다.**

---

## ID 생성 유틸 — 올바른 구현

```kotlin
// common/GenerateId.kt
package com.example.accountservice.common

import java.util.UUID

fun generateId(): String = UUID.randomUUID().toString().replace("-", "")
```

Kotlin에서는 최상위 함수(top-level function)로 선언할 수 있어 별도 유틸 클래스가 필요 없다. `object GenerateId { ... }` 싱글턴으로 감쌀 필요도 없다 — 파일 하나가 곧 모듈이다.

---

## Aggregate에서 사용 — 올바른 패턴

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
                this.accountId = generateId()   // 하이픈 제거된 32자리 hex
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

**신규 생성과 DB 복원의 구분**은 JPA Entity 특유의 두 단계 생성자 패턴으로 자연스럽게 해결된다:

- **신규 생성**: `Account.create(...)` — `companion object` 팩토리가 `generateId()`로 새 ID를 할당
- **DB 복원**: JPA(Hibernate)가 `protected constructor()`로 프록시를 만든 뒤 리플렉션으로 `@Id` 필드를 채운다. 애플리케이션 코드가 관여하지 않는다

`id: Long?`(JPA surrogate key, `@GeneratedValue`)와 `accountId: String`(도메인 식별자)를 분리한 것도 root 원칙과 일치한다: **DB가 생성하는 auto-increment 값은 오직 JPA 내부 식별자로만 쓰고, 도메인/외부에 노출하는 식별자는 항상 애플리케이션이 생성한 `accountId`를 사용한다.** `AccountController`, `AccountRepository`, 모든 Command/Result는 `accountId`만 참조하며 `id: Long`은 어디에도 노출되지 않는다.

---

## Repository 구현체에서 ID 처리

Repository는 Aggregate가 이미 가진 `accountId`를 그대로 영속화한다. DB에서 도메인 ID를 새로 발급하지 않는다.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt
override fun save(account: Account) {
    jpaRepository.save(account)   // account.accountId는 그대로 유지된 채 저장됨
}
```

---

## 하위 Entity ID

Aggregate 내부의 하위 Entity(`Transaction`)도 동일하게 `generateId()` 기반 32자리 hex 문자열을 사용한다.

```kotlin
// domain/Transaction.kt
companion object {
    fun create(accountId: String, type: TransactionType, amount: Money): Transaction =
        Transaction().apply {
            this.transactionId = generateId()   // Account와 동일한 ID 생성 규칙
            this.accountId = accountId
            this.type = type
            this.amount = amount
            this.createdAt = LocalDateTime.now()
        }
}
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 팩토리 함수 패턴
- [repository-pattern.md](repository-pattern.md) — Repository에서 Aggregate 저장
