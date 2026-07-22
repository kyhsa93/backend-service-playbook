# Testing Strategy (Spring Boot)

> For the framework-agnostic principles, see the root [testing.md](../../../../docs/architecture/testing.md).

## The 3-tier testing pyramid

`examples/` has all 3 tiers the root requires — Domain unit tests (`AccountTest`, `MoneyTest`), Application unit tests (`CreateAccountServiceTest`, `DepositServiceTest`), and E2E tests (`AccountControllerE2ETest`, `NotificationE2ETest`, Testcontainers-based).

| Layer | What it verifies | Dependency strategy | Current state |
|--------|----------|------------|----------|
| Domain unit tests | `Account`, `Money` invariants | No framework | Present (`AccountTest`, `MoneyTest`) |
| Application unit tests | `CreateAccountService`/`DepositService` coordination logic | Mocks `AccountRepository` (Mockito) | Present (`CreateAccountServiceTest`, `DepositServiceTest`) |
| E2E tests | The full Controller → Service → DB path | Testcontainers | Present (`AccountControllerE2ETest`, `NotificationE2ETest`) |

Below, all 3 tiers are defined in Java/Spring idiom.

---

## Testing framework — JUnit 5 + Mockito

`build.gradle` has `spring-boot-starter-test` (which includes JUnit 5, AssertJ, Mockito):

```groovy
// build.gradle — actual code
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:postgresql'
testImplementation 'org.testcontainers:localstack'
```

Since Mockito is already included in `spring-boot-starter-test`, **no additional dependency needs to be installed** for Application unit tests — you only need to write the Domain/Application unit tests themselves.

---

## Domain unit tests — pure Java, no framework

```java
// src/test/java/.../account/domain/AccountTest.java — actual code
package com.example.accountservice.account.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account createAccount() {
        return Account.create("owner-1", "owner-1@example.com", "KRW");
    }

    @Test
    void 계좌_생성_시_잔액은_0이고_ACTIVE_상태다() {
        Account account = createAccount();

        assertThat(account.getBalance().amount()).isEqualTo(0);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.pullDomainEvents()).hasSize(1)
                .first().isInstanceOf(AccountCreatedEvent.class);
    }

    @Test
    void 정지된_계좌에_입금하면_예외를_던진다() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(() -> account.deposit(1000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 잔액보다_큰_금액을_출금하면_예외를_던진다() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(() -> account.withdraw(2000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void 잔액이_0이_아닌_계좌를_종료하면_예외를_던진다() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO);
    }

    @Test
    void 입금하면_MoneyDepositedEvent가_수집된다() {
        Account account = createAccount();
        account.pullDomainEvents();   // clear the creation event

        account.deposit(5000);

        var events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(((MoneyDepositedEvent) events.get(0)).amount().amount()).isEqualTo(5000);
    }
}
```

- `Account`, `Money`, and `Transaction` are pure domain classes with no JPA annotations at all, so **testing is possible with just a static call to `Account.create(...)`, with no `@SpringBootTest`** (see [layer-architecture.md](layer-architecture.md)). Since no Spring context is started, these finish in milliseconds.
- `AssertJ`'s (included in `spring-boot-starter-test`) `assertThatThrownBy` + `extracting` verifies not just the exception type but the exact `ErrorCode` — asserting against an enum value rather than a string message means the test doesn't break if the message wording changes.
- `Money`'s (Value Object) record equality can also be verified here: `assertThat(new Money(1000, "KRW")).isEqualTo(new Money(1000, "KRW"))` — this is just confirming the `equals()` a record auto-generates, so it's not strictly necessary, but it has the effect of documenting the Value Object's contract.

---

## Application unit tests — mocking the Repository with Mockito

```java
// src/test/java/.../account/application/command/CreateAccountServiceTest.java — actual code
package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.example.accountservice.account.domain.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateAccountServiceTest {

    @Mock private AccountRepository accountRepository;

    private CreateAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreateAccountService(accountRepository);
    }

    @Test
    void 계좌_생성_시_저장되고_결과에_초기_잔액_0이_담긴다() {
        CreateAccountResult result =
                service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        assertThat(result.ownerId()).isEqualTo("owner-1");
        assertThat(result.balance().amount()).isEqualTo(0);
        verify(accountRepository).saveAccount(any());
    }
}
```

- **`@ExtendWith(MockitoExtension.class)` + `@Mock`**: Mockito mocks the interface (`AccountRepository`) via a proxy — a Java interface is inherently easy to mock (there's no issue mocking a `final` class the way Kotlin has), so no separate inline-mock library is needed.
- **The Repository mock uses the interface type** as-is — the root's principle of "mock via the abstract-class type, never mock a concrete class" maps directly to a plain interface in Java. `AccountRepositoryImpl` (the concrete class) is never mocked.
- Since `AccountRepository.saveAccount()` is also responsible for the Outbox write (see domain-events.md), it's sufficient for a Command Service unit test to verify only that `accountRepository.saveAccount()` was called exactly once — since the Command Service itself never references `OutboxPoller`/`OutboxConsumer` at all (a regression here is caught by the `outbox-drain-order` harness rule), there's no need to mock-verify the drain call in the first place. The actual content of the Outbox row (the serialized event payload) is verified in a separate test targeting `AccountRepositoryImpl`/`OutboxWriter`.
- An Application unit test verifies **only the coordination flow** — balance calculation or status-transition rules (business logic) are already verified by the Domain unit tests, so they aren't repeated here.

---

## E2E tests — Testcontainers

```java
// src/test/java/.../AccountControllerE2ETest.java — actual code (excerpt)
@Testcontainers
@SpringBootTest(classes = AccountServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Test
    void 생성_요청이_유효하면_201과_계좌_정보를_반환한다() {
        ResponseEntity<Map> response = post("/accounts", OWNER_ID, Map.of("currency", "KRW", "email", "owner-1@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

This file follows the root's E2E principles exactly: it verifies through a real HTTP request (`TestRestTemplate`) all the way down to a real DB (Testcontainers Postgres — a genuine container, not in-memory). `NotificationE2ETest` goes further, also spinning up LocalStack SES as a container, verifying the actual email-send path end-to-end (the SES API call → the `SentEmail` save → confirming the send via LocalStack's `_aws/ses` endpoint). Injecting a container's runtime port/credentials into the Spring context via `@DynamicPropertySource` is Testcontainers' standard idiom.

`AccountControllerE2ETest` broadly covers success/failure paths, authorization checks (a 404 when a different owner accesses it), and pagination across 26 tests — **this layer only needs to be extended, never rewritten.**

---

## Test file placement — the standard Gradle source set

```
src/
  main/java/.../account/
    domain/Account.java
    application/command/CreateAccountService.java
  test/java/.../account/
    domain/
      AccountTest.java                          ← Domain unit test (same package as the source, under src/test)
    application/command/
      CreateAccountServiceTest.java              ← Application unit test
    interfaces/rest/
      AccountControllerE2ETest.java              ← E2E test (actual location)
```

The standard Java/Gradle layout separates `src/main` and `src/test`, mirroring the same package structure inside each — unlike NestJS/TypeScript's convention of placing a `.spec.ts` right next to its source file, this follows the JVM build tool's (Gradle/Maven) `sourceSets` convention. E2E tests also live in the same `src/test/java` tree, not a separate top-level `test/` directory.

---

## Test naming — Korean method names (an already-established convention)

```java
// a pattern already in use in the actual code
@Test
void 생성_요청이_유효하면_201과_계좌_정보를_반환한다() { /* ... */ }
```

Java method names can use underscores and Korean characters as-is, with no backticks needed (Unicode identifiers are allowed). Instead of the root's English snake_case `<action>_when_<condition>_then_<expected_result>`, this repository has already adopted the convention of expressing test intent as **full Korean sentences** — the method name itself reads clearly with no `@DisplayName` annotation needed (and since test-report output shows the method name in Korean as-is, CI log readability is preserved too). New Domain/Application unit tests being added should follow this same pattern.

---

## Principle summary

- **All 3 tiers are implemented**: Domain (no framework) → Application (mocking the Repository with Mockito) → E2E (Testcontainers, already present).
- **Mockito is adopted**: already included in `spring-boot-starter-test`, so no additional dependency is needed. There's no restriction on mocking interfaces.
- **Domain tests verify business logic, and Application tests verify only the coordination flow** — never verify the same thing twice.
- **Korean method names are kept**: this is this repository's already-established convention.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Domain layer design (the target of unit tests)
- [layer-architecture.md](layer-architecture.md) — the Application Service structure
- [error-handling.md](error-handling.md) — the error response format to verify in E2E tests
