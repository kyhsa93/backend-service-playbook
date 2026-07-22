# Testing Strategy — Kotlin Spring Boot

> For the framework-agnostic principles, see [root testing.md](../../../../docs/architecture/testing.md).

## The 3 layers (Domain/Application/E2E)

`examples/` has all 3 layers of testing the root requires: `AccountTest.kt` (Domain unit), `CreateAccountServiceTest.kt`/`DepositServiceTest.kt` (Application unit, MockK), `AccountControllerE2ETest.kt` (Testcontainers-based E2E, Postgres + LocalStack SES). Below, all 3 layers are defined using Kotlin idioms and compared against the actual code.

| Layer | Verification scope | Dependency strategy | Current state |
|--------|----------|------------|----------|
| Domain unit tests | `Account` invariants (16 test cases) | no framework | **present** (`AccountTest.kt`) |
| Application unit tests | `CreateAccountService`/`DepositService` coordination logic | `AccountRepository` mocked with MockK | **present** (`CreateAccountServiceTest.kt`, `DepositServiceTest.kt`) |
| E2E tests | the full Controller → Service → DB path | Testcontainers | present (`AccountControllerE2ETest.kt`, `NotificationE2ETest.kt`) |

---

## Test framework choice — JUnit 5 + MockK

`build.gradle.kts` already has `io.mockk:mockk`, and the Application unit tests (`CreateAccountServiceTest.kt`, etc) use it. The E2E tests still use only real Testcontainers instances (no mocks). Below is the rationale for choosing MockK.

| | Mockito(-Kotlin) | **MockK (recommended)** |
|---|---|---|
| Mocking a final class | not possible by default — requires adding `mockito-inline` (a Kotlin class is `final` by default) | supported by default — no extra setup needed |
| DSL | needs the Java style (`when(...).thenReturn(...)`) + a Kotlin wrapper (`mockito-kotlin`) | a Kotlin-only DSL (`every { } returns`) — designed for Kotlin from the start |
| Matching `data class` arguments | sometimes needs a separate value-based matcher setup | naturally meshes with `data class` value equality |
| Relaxed mocks | none | `mockk(relaxed = true)` — a method call that isn't stubbed automatically returns a default value |

**Why MockK is recommended**: since a Kotlin class is `final` by default (`kotlin("plugin.spring")` only auto-`open`s `@Component`-family classes, and `AccountRepository` is an `interface` so there's no problem there, but Mockito needs extra setup when a concrete class must be mocked), MockK is the de facto standard for Kotlin projects. Since this repository defines its Repositories as `interface`, both libraries would technically work, but MockK is chosen because its `every { }`/`verify { }` DSL naturally meshes with `data class` Command/Result objects, and it keeps the whole codebase unified in Kotlin idiom.

```kotlin
// build.gradle.kts — actual code
testImplementation("io.mockk:mockk:1.13.13")
```

---

## Domain unit tests — plain Kotlin, no framework

```kotlin
// src/test/kotlin/.../account/domain/AccountTest.kt — actual code (excerpt, out of 16 total test cases)
package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountTest {

    private fun createAccount(currency: String = "KRW"): Account =
        Account.create(ownerId = "owner-1", currency = currency, email = "owner-1@example.com")

    @Test
    fun `creating an account starts with a 0 balance and the ACTIVE status`() {
        val account = createAccount()

        assertThat(account.balance.amount).isEqualTo(0)
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountCreatedEvent::class.java)
    }

    @Test
    fun `the account ID is a 32-character hex string without hyphens`() {
        val account = createAccount()

        assertThat(account.accountId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `depositing to a suspended account throws an exception`() {
        val account = createAccount()
        account.suspend()

        assertThrows<DepositRequiresActiveAccountException> { account.deposit(1000) }
    }

    @Test
    fun `depositing an amount of 0 or less throws an exception`() {
        val account = createAccount()

        assertThrows<InvalidAmountException> { account.deposit(0) }
    }

    @Test
    fun `withdrawing more than the balance throws an exception`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<InsufficientBalanceException> { account.withdraw(2000) }
    }

    @Test
    fun `closing an account with a non-zero balance throws an exception`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<AccountBalanceNotZeroException> { account.close() }
    }

    @Test
    fun `depositing collects a MoneyDepositedEvent`() {
        val account = createAccount()
        account.pullDomainEvents()   // clear the creation event

        account.deposit(5000)

        val events = account.pullDomainEvents()
        assertThat(events).hasSize(1)
        assertThat((events.first() as MoneyDepositedEvent).amount.amount).isEqualTo(5000)
    }

    // ... the remaining cases (suspend/reactivate/close state transitions, pullPendingTransactions(), etc) are in the full AccountTest.kt
}
```

- Since neither `Account`, `Money`, nor `Transaction` has any Spring annotation, testing is possible with just a plain `Account.create(...)` call, with no `@SpringBootTest` — it finishes in milliseconds.
- AssertJ (included with `spring-boot-starter-test`) + JUnit 5's `assertThrows<T>()` (a Kotlin reified-generic extension function) verify the exception type — pinpointing the exact concrete subtype of `sealed class AccountException`.
- `Money`'s (a Value Object's) `data class` equality can also be verified here: confirming that `Money(1000, "KRW") == Money(1000, "KRW")` is `true` via AssertJ's `isEqualTo` — it passes with no separate `equals()` implementation needed.

---

## Application unit tests — replacing the Repository with MockK

```kotlin
// src/test/kotlin/.../account/application/command/CreateAccountServiceTest.kt — actual code
package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountRepository
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateAccountServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = CreateAccountService(accountRepository)

    @Test
    fun `creating an account saves it and returns a result with an initial balance of 0`() {
        val result = service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.balance.amount).isEqualTo(0)
        verify(exactly = 1) { accountRepository.saveAccount(any()) }
    }
}
```

Since the Command Service calls `AccountRepository.saveAccount()` and returns right away, an Application unit test only needs to verify "did the save happen" — draining the Outbox (`OutboxPoller`/`OutboxConsumer`) is a separate component the Command Service never references, so it isn't something this test mocks. The behavior of `OutboxPoller`/`OutboxConsumer`/`EventHandlerRegistry` themselves (SQS publish/receive, handler routing) is outside this file's scope — if needed, it's covered by a separate unit test or by `NotificationE2ETest` (an end-to-end LocalStack SQS/SES verification).

This test doesn't go as far as verifying that the `AccountCreated` event actually leads to a notification (that's already finished the moment it's written to the Outbox, inside `Repository.saveAccount()`) — the per-event-type notification-sending logic itself is either unit-tested separately against `application/event/AccountCreatedEventHandler`, etc, or verified end-to-end by `NotificationE2ETest` against real LocalStack SES.

- `mockk<AccountRepository>(relaxed = true)`: `relaxed = true` automatically returns a default value (an empty list, `Unit`, etc) for any unstubbed method call — you don't need to fill in `every { } returns Unit` every time for a method with no return value, like `saveAccount()`.
- **The Repository mock uses the `interface` type** as-is — the root's "mock as the abstract class type, never mock a concrete class" corresponds directly to the interface in Kotlin.
- An Application unit test verifies **only the coordination flow** — balance calculation or state-transition rules (business logic) are already verified by the Domain unit tests, so they aren't repeated here.
- `DepositServiceTest.kt` follows the same pattern too (mocking only `AccountRepository` with MockK) — these two files are reused as-is as the template when adding an Application unit test for each new Command Service.

---

## E2E tests — Testcontainers

```kotlin
// src/test/kotlin/.../AccountControllerE2ETest.kt — actual code (partial)
@Testcontainers
@SpringBootTest(classes = [AccountServiceApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerE2ETest {

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0")).withServices(LocalStackContainer.Service.SES)

        @DynamicPropertySource @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }   // test-only automatic schema creation
            registry.add("AWS_ENDPOINT_URL") { localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString() }
        }
    }

    @Test
    fun `a valid creation request returns 201 and the account info`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to "$OWNER_ID@example.com"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        // ...
    }
}
```

This file follows the root's E2E principle exactly: it verifies a real HTTP request (`TestRestTemplate`), a real DB (Testcontainers Postgres — a genuine container, not in-memory), and even the real SES send path (LocalStack). Injecting the container's runtime port/credentials into the Spring context via `@DynamicPropertySource` is also Testcontainers' standard idiom. 22+ test cases broadly cover success/failure paths, authorization checks (404 when a different owner tries to access), and pagination — **this layer only needs to be extended, never rewritten.**

---

## Test file placement

```
src/
  main/kotlin/.../account/
    domain/Account.kt
    application/command/CreateAccountService.kt
  test/kotlin/.../account/
    domain/
      AccountTest.kt                        ← Domain unit test (mirrors the source's package, not placed next to the source — Gradle's standard layout)
    application/command/
      CreateAccountServiceTest.kt            ← Application unit test
    interfaces/rest/
      AccountControllerE2ETest.kt            ← E2E test (its actual location)
```

The Kotlin/Gradle standard layout separates `src/main` and `src/test`, mirroring the same package structure inside each — unlike NestJS/TypeScript's convention of placing a `.spec.ts` right next to its source file, this follows the `sourceSets` convention of JVM build tools (Gradle/Maven). The E2E tests also live inside the same `src/test/kotlin` tree rather than a separate top-level `test/` directory — unlike Jest-based NestJS, JUnit 5 distinguishes tests by source set itself, so a separate directory split isn't required.

---

## Test naming — natural-language backtick function names (an already-established convention)

```kotlin
// a pattern already in use in the actual code
@Test
fun `a valid creation request returns 201 and the account info`() { /* ... */ }
```

Kotlin lets you use spaces and natural language directly in a function name when wrapped in backticks (`` ` ``). Instead of the root's `<action>_when_<condition>_then_<expected_result>` snake_case naming, this repository has already adopted the Kotlin-specific idiom of expressing test intent as a **complete natural-language sentence**. Newly added Domain/Application unit tests follow this same pattern.

---

## Principle summary

- **All 3 layers are implemented**: Domain (no framework) → Application (Repository mocked with MockK) → E2E (Testcontainers, already present).
- **MockK is adopted**: friendly with Kotlin final classes, and the `every`/`verify`/`slot` DSL naturally meshes with Kotlin idiom.
- **A Domain test verifies business logic**, while an Application test verifies only the coordination flow — never verify the same thing twice.
- **Natural-language backtick function names are kept**: an already-established convention of this repository.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Domain layer design (what the unit tests target)
- [layer-architecture.md](layer-architecture.md) — Application Service structure
- [error-handling.md](error-handling.md) — the error response format verified in the E2E tests
