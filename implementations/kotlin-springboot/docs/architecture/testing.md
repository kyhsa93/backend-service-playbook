# 테스트 전략 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root testing.md](../../../../docs/architecture/testing.md) 참조.

## 알려진 갭 — 현재는 E2E 레이어만 존재한다

`examples/`의 유일한 테스트는 `src/test/kotlin/.../AccountControllerE2ETest.kt`다 — Testcontainers(Postgres + LocalStack SES)로 실제 HTTP 요청과 이메일 발송까지 검증하는 훌륭한 E2E 테스트이지만, root가 요구하는 3계층 중 **Domain 단위 테스트**와 **Application 단위 테스트**가 하나도 없다. 테스트 피라미드가 최상위 계층(가장 느리고 비싼 테스트)에만 존재하는 역피라미드 상태다. 아래에서 3계층 모두를 Kotlin 관용으로 정의한다.

| 레이어 | 검증 범위 | 의존성 전략 | 현재 상태 |
|--------|----------|------------|----------|
| Domain 단위 테스트 | `Account`, `Money`, `Transaction` 불변식 | 프레임워크 없음 | **없음** |
| Application 단위 테스트 | `CreateAccountService` 등 조율 로직 | `AccountRepository`를 mock | **없음** |
| E2E 테스트 | Controller → Service → DB 전체 경로 | Testcontainers | 있음 (`AccountControllerE2ETest.kt`) |

---

## 테스트 프레임워크 선택 — JUnit 5 + MockK

이 저장소는 아직 어떤 테스트에서도 mocking 라이브러리를 쓰지 않는다(`build.gradle.kts`에 Mockito/MockK 모두 없음, E2E 테스트는 Testcontainers 실제 인스턴스만 사용). 신규로 Application 단위 테스트를 추가할 때 무엇을 쓸지 정해야 한다.

| | Mockito(-Kotlin) | **MockK (권장)** |
|---|---|---|
| final 클래스 mock | 기본 불가 — `mockito-inline` 추가 필요 (Kotlin 클래스는 기본 `final`) | 기본 지원 — 별도 설정 불필요 |
| DSL | Java 스타일(`when(...).thenReturn(...)`) + Kotlin 래퍼(`mockito-kotlin`) 필요 | Kotlin 전용 DSL(`every { } returns`) — 애초에 Kotlin으로 설계됨 |
| `data class` 인자 매칭 | 값 기반 matcher 별도 구성 필요한 경우 있음 | `data class` 값 동등성과 자연스럽게 맞물림 |
| relaxed mock | 없음 | `mockk(relaxed = true)` — 사용하지 않는 메서드는 기본값 자동 반환 |

**MockK를 권장하는 이유**: Kotlin 클래스는 기본 `final`이라(`kotlin("plugin.spring")`이 `@Component` 계열만 자동으로 `open` 처리하고, `AccountRepository`는 `interface`라 문제없지만 구체 클래스를 mock해야 하는 경우 Mockito는 추가 설정이 필요하다) MockK가 Kotlin 프로젝트의 사실상 표준이다. 이 저장소는 Repository를 `interface`로 정의하므로 두 라이브러리 모두 동작은 하지만, `every { }`/`verify { }` DSL이 `data class` Command/Result와 자연스럽게 어우러지고 코드 전체가 Kotlin 관용으로 통일된다는 점에서 MockK를 선택한다.

```kotlin
// build.gradle.kts — 추가 필요
testImplementation("io.mockk:mockk:1.13.13")
```

---

## Domain 단위 테스트 — 프레임워크 없이 순수 Kotlin

```kotlin
// src/test/kotlin/.../account/domain/AccountTest.kt — 제안
package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountTest {

    private fun createAccount(currency: String = "KRW"): Account =
        Account.create(ownerId = "owner-1", currency = currency, email = "owner-1@example.com")

    @Test
    fun `계좌 생성 시 잔액은 0이고 ACTIVE 상태다`() {
        val account = createAccount()

        assertThat(account.balance.amount).isEqualTo(0)
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.pullDomainEvents()).hasSize(1).first().isInstanceOf(AccountCreatedEvent::class.java)
    }

    @Test
    fun `정지된 계좌에 입금하면 예외를 던진다`() {
        val account = createAccount()
        account.suspend()

        assertThrows<DepositRequiresActiveAccountException> { account.deposit(1000) }
    }

    @Test
    fun `0 이하 금액을 입금하면 예외를 던진다`() {
        val account = createAccount()

        assertThrows<InvalidAmountException> { account.deposit(0) }
    }

    @Test
    fun `잔액보다 큰 금액을 출금하면 예외를 던진다`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<InsufficientBalanceException> { account.withdraw(2000) }
    }

    @Test
    fun `잔액이 0이 아닌 계좌를 종료하면 예외를 던진다`() {
        val account = createAccount()
        account.deposit(1000)

        assertThrows<AccountBalanceNotZeroException> { account.close() }
    }

    @Test
    fun `입금하면 MoneyDepositedEvent가 수집된다`() {
        val account = createAccount()
        account.pullDomainEvents()   // 생성 이벤트 비우기

        account.deposit(5000)

        val events = account.pullDomainEvents()
        assertThat(events).hasSize(1)
        assertThat((events.first() as MoneyDepositedEvent).amount.amount).isEqualTo(5000)
    }
}
```

- `Account`, `Money`, `Transaction` 어디에도 Spring 애노테이션이 없으므로 `@SpringBootTest` 없이 순수 `Account.create(...)` 호출만으로 테스트가 가능하다 — 밀리초 단위로 끝난다.
- assertJ(`spring-boot-starter-test`에 포함) + JUnit 5 `assertThrows<T>()`(Kotlin의 reified 제네릭 확장 함수)로 예외 타입을 검증한다 — `sealed class AccountException`의 구체 하위 타입을 정확히 지정한다.
- `Money`(Value Object)의 `data class` 동등성도 여기서 함께 검증할 수 있다: `Money(1000, "KRW") == Money(1000, "KRW")`가 `true`임을 assertJ의 `isEqualTo`로 확인 — 별도 `equals()` 구현이 없어도 통과한다.

---

## Application 단위 테스트 — MockK로 Repository 대체

```kotlin
// src/test/kotlin/.../account/application/command/CreateAccountServiceTest.kt — 제안
package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class CreateAccountServiceTest {

    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = CreateAccountService(accountRepository, eventPublisher)

    @Test
    fun `계좌 생성 시 저장되고 결과에 초기 잔액 0이 담긴다`() {
        val result = service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.balance.amount).isEqualTo(0)
        verify(exactly = 1) { accountRepository.save(any()) }
    }

    @Test
    fun `생성된 Account가 발행하는 도메인 이벤트가 eventPublisher로 전달된다`() {
        val eventSlot = slot<Any>()
        every { eventPublisher.publishEvent(capture(eventSlot)) } returns Unit

        service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(eventSlot.captured).isInstanceOf(com.example.accountservice.account.domain.AccountCreatedEvent::class.java)
    }
}
```

- `mockk<AccountRepository>(relaxed = true)`: `relaxed = true`는 스텁하지 않은 메서드 호출에 기본값(빈 리스트, `Unit` 등)을 자동 반환한다 — `save()`처럼 반환값이 없는 메서드를 매번 `every { } returns Unit`으로 채우지 않아도 된다.
- `slot<Any>()` + `capture(...)`: MockK의 캡처 기능으로 `publishEvent()`에 전달된 실제 이벤트 객체를 꺼내 타입/필드를 검증한다 — Mockito의 `ArgumentCaptor`에 대응하지만 문법이 더 간결하다.
- **Repository mock은 `interface` 타입**을 그대로 사용한다 — root의 "abstract class 타입으로 mock, 구체 클래스 mock 금지"가 Kotlin에서는 인터페이스 그대로 대응된다.
- Application 단위 테스트는 **조율 흐름만** 검증한다 — 잔액 계산이나 상태 전이 규칙(비즈니스 로직)은 Domain 단위 테스트가 이미 검증했으므로 여기서 반복하지 않는다.

---

## E2E 테스트 — 이미 올바르게 구현됨 (Testcontainers)

```kotlin
// src/test/kotlin/.../AccountControllerE2ETest.kt — 실제 코드 (일부)
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
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }   // 테스트 전용 스키마 자동 생성
            registry.add("AWS_ENDPOINT_URL") { localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString() }
        }
    }

    @Test
    fun `생성 요청이 유효하면 201과 계좌 정보를 반환한다`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to "$OWNER_ID@example.com"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        // ...
    }
}
```

이 파일은 root의 E2E 원칙을 정확히 따른다: 실제 HTTP 요청(`TestRestTemplate`), 실제 DB(Testcontainers Postgres — in-memory가 아닌 진짜 컨테이너), 실제 SES 발송 경로(LocalStack)까지 검증한다. `@DynamicPropertySource`로 컨테이너의 런타임 포트/자격증명을 Spring 컨텍스트에 주입하는 것도 Testcontainers의 표준 관용구다. 22개 이상의 테스트 케이스가 성공/실패 경로, 권한 검증(다른 소유자 접근 시 404), 페이지네이션까지 폭넓게 다룬다 — **이 레이어는 확장만 하면 되고 재작성이 필요 없다.**

---

## 테스트 파일 배치

```
src/
  main/kotlin/.../account/
    domain/Account.kt
    application/command/CreateAccountService.kt
  test/kotlin/.../account/
    domain/
      AccountTest.kt                        ← Domain 단위 테스트 (소스와 대응되는 패키지, 소스 옆은 아님 — Gradle 표준 레이아웃)
    application/command/
      CreateAccountServiceTest.kt            ← Application 단위 테스트
    interfaces/rest/
      AccountControllerE2ETest.kt            ← E2E 테스트 (실제 위치)
```

Kotlin/Gradle 표준 레이아웃은 `src/main`과 `src/test`를 분리하고 그 안에서 동일한 패키지 구조를 미러링한다 — NestJS/TypeScript처럼 소스 파일 바로 옆에 `.spec.ts`를 두는 것과 달리, JVM 빌드 도구(Gradle/Maven)의 `sourceSets` 관례를 따른다. E2E 테스트도 별도 `test/` 상위 디렉토리가 아니라 같은 `src/test/kotlin` 트리 안에 있다 — Jest 기반 NestJS와 달리 JUnit 5는 소스 세트 자체로 테스트를 구분하므로 별도 디렉토리 분리가 필수는 아니다.

---

## 테스트 네이밍 — 한글 backtick 함수명 (이미 확립된 관례)

```kotlin
// 실제 코드에서 이미 사용 중인 패턴
@Test
fun `생성 요청이 유효하면 201과 계좌 정보를 반환한다`() { /* ... */ }
```

Kotlin은 함수명을 백틱(`` ` ``)으로 감싸면 공백과 자연어를 그대로 사용할 수 있다 — root의 `<행위>_when_<조건>_then_<기대_결과>` 스네이크 케이스 네이밍 대신, 이 저장소는 **완전한 한글 문장**으로 테스트 의도를 표현하는 Kotlin 고유의 관용구를 이미 채택하고 있다. 새로 추가하는 Domain/Application 단위 테스트도 이 패턴을 따른다.

---

## 원칙 요약

- **3계층 모두 구현**: Domain(프레임워크 없음) → Application(MockK로 Repository mock) → E2E(Testcontainers, 이미 있음).
- **MockK 채택**: Kotlin final 클래스 친화적, `every`/`verify`/`slot` DSL이 Kotlin 관용과 자연스럽게 맞물림.
- **Domain 테스트가 비즈니스 로직을 검증**하고, Application 테스트는 조율 흐름만 검증한다 — 중복 검증 금지.
- **한글 backtick 함수명 유지**: 이미 확립된 이 저장소의 관례.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 레이어 설계 (단위 테스트 대상)
- [layer-architecture.md](layer-architecture.md) — Application Service 구조
- [error-handling.md](error-handling.md) — E2E 테스트에서 검증할 에러 응답 형식
