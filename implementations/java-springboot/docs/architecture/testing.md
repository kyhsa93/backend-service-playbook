# 테스트 전략 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [testing.md](../../../../docs/architecture/testing.md) 참고.

## 3계층 테스트 피라미드

`examples/`에는 root가 요구하는 3계층이 모두 있다 — Domain 단위 테스트(`AccountTest`, `MoneyTest`), Application 단위 테스트(`CreateAccountServiceTest`, `DepositServiceTest`), E2E 테스트(`AccountControllerE2ETest`, `NotificationE2ETest`, Testcontainers 기반).

| 레이어 | 검증 범위 | 의존성 전략 | 현재 상태 |
|--------|----------|------------|----------|
| Domain 단위 테스트 | `Account`, `Money` 불변식 | 프레임워크 없음 | 있음 (`AccountTest`, `MoneyTest`) |
| Application 단위 테스트 | `CreateAccountService`/`DepositService` 조율 로직 | `AccountRepository`를 mock(Mockito) | 있음 (`CreateAccountServiceTest`, `DepositServiceTest`) |
| E2E 테스트 | Controller → Service → DB 전체 경로 | Testcontainers | 있음 (`AccountControllerE2ETest`, `NotificationE2ETest`) |

아래에서 3계층 모두를 Java/Spring 관용으로 정의한다.

---

## 테스트 프레임워크 — JUnit 5 + Mockito

`build.gradle`은 `spring-boot-starter-test`(JUnit 5, AssertJ, Mockito 포함)를 갖고 있다:

```groovy
// build.gradle — 실제 코드
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:postgresql'
testImplementation 'org.testcontainers:localstack'
```

`spring-boot-starter-test`에 Mockito가 이미 포함되어 있으므로 Application 단위 테스트를 위한 **추가 의존성 설치가 필요 없다** — Domain/Application 단위 테스트를 새로 작성하기만 하면 된다.

---

## Domain 단위 테스트 — 프레임워크 없이 순수 Java

```java
// src/test/java/.../account/domain/AccountTest.java — 실제 코드
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
        account.pullDomainEvents();   // 생성 이벤트 비우기

        account.deposit(5000);

        var events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(((MoneyDepositedEvent) events.get(0)).amount().amount()).isEqualTo(5000);
    }
}
```

- `Account`, `Money`, `Transaction`이 JPA 애노테이션을 갖고 있음에도(layer-architecture.md의 알려진 gap) **`@SpringBootTest` 없이 `Account.create(...)` 정적 호출만으로 테스트가 가능하다** — JPA 애노테이션은 런타임 메타데이터일 뿐 클래스 로딩/인스턴스화 자체를 방해하지 않기 때문이다. Spring 컨텍스트를 띄우지 않으므로 밀리초 단위로 끝난다.
- `AssertJ`(`spring-boot-starter-test`에 포함)의 `assertThatThrownBy` + `extracting`으로 예외 타입뿐 아니라 `ErrorCode`까지 정밀하게 검증한다 — 문자열 메시지가 아니라 enum 값으로 단언하므로 메시지 문구가 바뀌어도 테스트가 깨지지 않는다.
- `Money`(Value Object)의 record 동등성도 여기서 함께 검증할 수 있다: `assertThat(new Money(1000, "KRW")).isEqualTo(new Money(1000, "KRW"))` — record가 자동 생성한 `equals()`를 확인하는 것뿐이므로 필수는 아니지만, Value Object 계약을 문서화하는 효과가 있다.

---

## Application 단위 테스트 — Mockito로 Repository mock

```java
// src/test/java/.../account/application/command/CreateAccountServiceTest.java — 실제 코드
package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateAccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OutboxRelay outboxRelay;

    private CreateAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreateAccountService(accountRepository, outboxRelay);
    }

    @Test
    void 계좌_생성_시_저장되고_결과에_초기_잔액_0이_담긴다() {
        CreateAccountResult result = service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        assertThat(result.ownerId()).isEqualTo("owner-1");
        assertThat(result.balance().amount()).isEqualTo(0);
        verify(accountRepository).save(any());
    }

    @Test
    void 계좌_저장_직후_OutboxRelay가_드레인을_한_번_호출한다() {
        service.create(new CreateAccountCommand("owner-1", "owner-1@example.com", "KRW"));

        verify(outboxRelay).processPending();
    }
}
```

- **`@ExtendWith(MockitoExtension.class)` + `@Mock`**: Mockito가 인터페이스(`AccountRepository`)를 프록시로 mock한다 — Java 인터페이스는 원래 mock이 쉬우므로(Kotlin처럼 `final` 클래스 mock 문제가 없다) 별도 인라인 mock 라이브러리가 필요 없다.
- **Repository mock은 인터페이스 타입**을 그대로 사용한다 — root의 "abstract class 타입으로 mock, 구체 클래스 mock 금지" 원칙이 Java에서는 인터페이스 그대로 대응된다. `AccountRepositoryImpl`(구체 클래스)을 mock하지 않는다.
- `AccountRepository.save()`가 Outbox 저장까지 책임지므로(domain-events.md 참고), Command Service 단위 테스트는 `accountRepository.save()` 직후 `outboxRelay.processPending()`이 정확히 한 번 호출되는지만 검증하면 충분하다 — Outbox 행 내용 자체(직렬화된 이벤트 payload)는 `AccountRepositoryImpl`/`OutboxWriter`를 대상으로 한 별도 테스트에서 검증한다.
- Application 단위 테스트는 **조율 흐름만** 검증한다 — 잔액 계산이나 상태 전이 규칙(비즈니스 로직)은 Domain 단위 테스트가 이미 검증했으므로 여기서 반복하지 않는다.

---

## E2E 테스트 — Testcontainers

```java
// src/test/java/.../AccountControllerE2ETest.java — 실제 코드 (일부)
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

이 파일은 root의 E2E 원칙을 정확히 따른다: 실제 HTTP 요청(`TestRestTemplate`), 실제 DB(Testcontainers Postgres — in-memory가 아닌 진짜 컨테이너)까지 검증한다. `NotificationE2ETest`는 여기에 더해 LocalStack SES까지 컨테이너로 띄워 실제 이메일 발송 경로(SES API 호출 → `SentEmail` 저장 → LocalStack의 `_aws/ses` 엔드포인트로 발송 확인)를 끝까지 검증한다. `@DynamicPropertySource`로 컨테이너의 런타임 포트/자격증명을 Spring 컨텍스트에 주입하는 것이 Testcontainers의 표준 관용구다.

`AccountControllerE2ETest`는 26개 테스트로 성공/실패 경로, 권한 검증(다른 소유자 접근 시 404), 페이지네이션까지 폭넓게 다룬다 — **이 레이어는 확장만 하면 되고 재작성이 필요 없다.**

---

## 테스트 파일 배치 — Gradle 표준 소스셋

```
src/
  main/java/.../account/
    domain/Account.java
    application/command/CreateAccountService.java
  test/java/.../account/
    domain/
      AccountTest.java                          ← Domain 단위 테스트 (소스와 동일 패키지, src/test 아래)
    application/command/
      CreateAccountServiceTest.java              ← Application 단위 테스트
    interfaces/rest/
      AccountControllerE2ETest.java              ← E2E 테스트 (실제 위치)
```

Java/Gradle 표준 레이아웃은 `src/main`과 `src/test`를 분리하고 그 안에서 동일한 패키지 구조를 미러링한다 — NestJS/TypeScript처럼 소스 파일 바로 옆에 `.spec.ts`를 두는 것과 달리, JVM 빌드 도구(Gradle/Maven)의 `sourceSets` 관례를 따른다. E2E 테스트도 별도 최상위 `test/` 디렉토리가 아니라 같은 `src/test/java` 트리 안에 있다.

---

## 테스트 네이밍 — 한글 메서드명 (이미 확립된 관례)

```java
// 실제 코드에서 이미 사용 중인 패턴
@Test
void 생성_요청이_유효하면_201과_계좌_정보를_반환한다() { /* ... */ }
```

Java 메서드명은 백틱 없이도 밑줄과 한글을 그대로 쓸 수 있다(유니코드 식별자 허용). root의 `<행위>_when_<조건>_then_<기대_결과>` 영문 스네이크 케이스 대신, 이 저장소는 **완전한 한글 문장**으로 테스트 의도를 표현하는 관례를 이미 채택하고 있다 — `@DisplayName` 애노테이션 없이도 메서드명 자체가 읽힌다(단, 테스트 리포트의 메서드명 표시가 한글 그대로 나오므로 CI 로그 가독성도 그대로 유지된다). 새로 추가하는 Domain/Application 단위 테스트도 이 패턴을 따른다.

---

## 원칙 요약

- **3계층 모두 구현**: Domain(프레임워크 없음) → Application(Mockito로 Repository mock) → E2E(Testcontainers, 이미 있음).
- **Mockito 채택**: `spring-boot-starter-test`에 이미 포함되어 추가 의존성 불필요. 인터페이스 mock에 제약 없음.
- **Domain 테스트가 비즈니스 로직을 검증**하고, Application 테스트는 조율 흐름만 검증한다 — 중복 검증 금지.
- **한글 메서드명 유지**: 이미 확립된 이 저장소의 관례.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 레이어 설계 (단위 테스트 대상)
- [layer-architecture.md](layer-architecture.md) — Application Service 구조
- [error-handling.md](error-handling.md) — E2E 테스트에서 검증할 에러 응답 형식
