# 크로스 도메인 호출 패턴 (Spring Boot)

> 언제 동기(Adapter) vs 비동기(Integration Event)를 선택할지의 원칙은 루트 [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 참고한다. 이 문서는 그 두 패턴을 Spring으로 구현하는 방법을 다룬다.

## 이 저장소의 현재 상태

`examples/`에는 `account`와 `card` 두 Bounded Context가 있다. `notification`(Technical Service, [directory-structure.md](directory-structure.md) 참고)은 별도 BC가 아니라 `account` 내부에 배치된 기술 서비스다.

- **Card → Account (동기 Adapter/ACL)**: 카드 발급 시 연결 계좌의 존재·활성 여부를 그 요청 안에서 즉시 확인해야 하므로 동기 Adapter 패턴을 쓴다.
- **Account → Card (비동기 Integration Event)**: 계좌 정지/해지가 연결된 카드 전부의 상태를 바꾸지만, 그 반영이 계좌 커맨드의 응답을 막을 이유는 없고 Card BC가 몰라도(존재하지 않아도) Account BC가 동작해야 하므로 Outbox 기반 Integration Event로 전파한다.

아래 두 절이 각각을 실제 코드로 보여준다.

---

## 패턴 1 — 동기 Adapter (Card BC가 Account BC를 조회)

### 원칙

1. **Application Service는 다른 BC의 Service/Repository를 직접 주입받지 않는다.** 대신 자신의 `application/adapter/`에 정의한 인터페이스를 통해서만 호출한다.
2. **Adapter 인터페이스는 호출하는 쪽(Card BC)의 `application/adapter/`에** 정의한다 — 호출받는 쪽(Account BC)이 아니다. 필요한 형태를 요구하는 쪽이 계약을 정의한다(Repository 패턴과 동일한 의존성 역전).
3. **Adapter 구현체는 호출하는 쪽(Card BC)의 `infrastructure/`에** 두고, Account BC가 노출한 읽기 인터페이스(`AccountQuery`)를 주입받아 위임한다.
4. **Adapter를 통해 다른 BC의 쓰기 메서드를 호출하지 않는다.** 조회(ACL)만 한다 — 상태 변경이 필요하면 Integration Event로 전환한다(아래 패턴 2).

### Step 1 — Card BC의 `application/adapter/`에 인터페이스 정의

```java
// card/application/adapter/AccountAdapter.java — 인터페이스 (호출하는 쪽이 소유)
package com.example.accountservice.card.application.adapter;

import java.util.Optional;

public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    // Card BC가 소유하는 최소 계좌 뷰 — Account BC의 AccountStatus enum을 그대로 노출하지 않고
    // active(boolean)로 번역한다. 상류(Account) 모델 변경이 Card 도메인으로 누수되지 않게 하는
    // 것이 ACL의 목적이다.
    record AccountView(String accountId, boolean active) {}
}
```

### Step 2 — Card BC의 `infrastructure/`에 구현체 작성

```java
// card/infrastructure/AccountAdapterImpl.java — 구현체 (호출하는 쪽이 소유)
package com.example.accountservice.card.infrastructure;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.card.application.adapter.AccountAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AccountAdapterImpl implements AccountAdapter {

    private final AccountQuery accountQuery;   // Account BC가 노출하는 읽기 전용 인터페이스

    @Override
    public Optional<AccountView> findAccount(String accountId, String ownerId) {
        return accountQuery.findByAccountIdAndOwnerId(accountId, ownerId)
                .map(account -> new AccountView(account.getAccountId(), account.getStatus() == AccountStatus.ACTIVE));
    }
}
```

- **Account BC의 실제 인터페이스(`AccountQuery`)를 주입받는 것은 Adapter 구현체(`infrastructure/`)뿐이다** — `AccountQuery`는 Spring 빈(`AccountRepositoryImpl`이 구현)이므로 `@Component`가 생성자로 주입받는 데 별도의 DI 설정이 필요 없다(같은 `ApplicationContext` 안이면 타입으로 자동 바인딩된다 — [module-pattern.md](module-pattern.md) 참고).
- Account BC의 "계좌 없음" 신호는 예외가 아니라 `Optional.empty()`이므로, 그 신호를 그대로 Card가 이해하는 `Optional.empty()`로 번역한다 — Account BC의 예외 타입이 Card 도메인으로 누수되지 않는다.

### Step 3 — Card BC의 Application Service에서 Adapter 사용

```java
// card/application/command/IssueCardService.java
@Service
@RequiredArgsConstructor
public class IssueCardService {

    private final CardRepository cardRepository;
    private final AccountAdapter accountAdapter;   // 구체 타입(AccountAdapterImpl)이 아니라 인터페이스에 의존

    public IssueCardResult issue(IssueCardCommand command) {
        AccountAdapter.AccountView account = accountAdapter.findAccount(command.accountId(), command.requesterId())
                .orElseThrow(() -> new CardException(
                        CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND, "연결할 계좌를 찾을 수 없습니다."));
        if (!account.active()) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT, "활성 상태의 계좌만 카드를 발급할 수 있습니다.");
        }

        Card card = Card.issue(command.accountId(), command.requesterId(), command.brand());
        cardRepository.save(card);
        return new IssueCardResult(card.getCardId(), card.getAccountId(), card.getOwnerId(),
                card.getBrand(), card.getStatus().name(), card.getCreatedAt());
    }
}
```

- `IssueCardService`는 `AccountAdapter` **인터페이스**에만 의존한다 — `AccountAdapterImpl`, 나아가 `AccountQuery`의 존재 자체를 모른다.
- 테스트 시 `AccountAdapter`를 Mockito로 mock하면 Account BC 없이 `IssueCardService`를 단위 테스트할 수 있다(`card/application/command/IssueCardServiceTest.java` 참고).

### 왜 인터페이스가 필요한가

- **의존 방향 오염 방지**: Card BC의 Application 레이어가 Account BC의 구체 타입을 import하면, Account BC 내부 구조 변경이 Card BC의 컴파일을 깨뜨린다. `AccountAdapter` 인터페이스가 이 결합을 끊는다.
- **불필요한 노출 차단**: `AccountQuery`가 갖는 메서드 중 Card BC가 필요한 것은 1개(`findByAccountIdAndOwnerId`)뿐이다. Adapter 인터페이스는 그 1개만 노출한다.
- **테스트 격리**: `AccountAdapter`를 mock하면 Account BC(및 그 Repository, DB 접근)를 부팅하지 않고도 Card BC 단위 테스트가 가능하다.

---

## 패턴 2 — 비동기 Integration Event (Account BC → Card BC)

계좌가 정지/해지되면 연결된 카드 전부의 상태가 바뀌어야 하지만, 이 반영은 계좌 커맨드의 응답을 막을 이유가 없고(최종 일관성으로 충분) Account BC가 Card BC의 존재를 알아야 할 이유도 없다. 이런 "상태 변경을 다른 BC에 전파"하는 경우가 Adapter가 아니라 Outbox 기반 Integration Event를 쓰는 신호다([domain-events.md](domain-events.md) 참고).

### Step 1 — Account BC가 공개 계약(Integration Event)을 정의한다

```java
// account/application/integrationevent/AccountSuspendedIntegrationEventV1.java
package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

public record AccountSuspendedIntegrationEventV1(String accountId, LocalDateTime suspendedAt) {
    public static final String EVENT_TYPE = "account.suspended.v1";
}
```

내부 Domain Event(`AccountSuspendedEvent`)와 분리된 별도 클래스다 — 이름·스키마·버전(`.v1`)이 외부에 공개하는 계약이므로, 내부 Domain Event의 필드가 바뀌어도 이 계약은 명시적으로만 바꾼다.

### Step 2 — Account BC의 기존 Domain Event 핸들러가 변환해 Outbox에 적재한다

```java
// account/application/event/AccountSuspendedEventHandler.java (발췌)
@Override
public void handle(String payload) throws Exception {
    AccountSuspendedEvent event = objectMapper.readValue(payload, AccountSuspendedEvent.class);

    // 외부 BC(Card 등)에 알리는 Integration Event를 같은 Outbox 트랜잭션에 적재한다.
    outboxWriter.save(AccountSuspendedIntegrationEventV1.EVENT_TYPE,
            new AccountSuspendedIntegrationEventV1(event.accountId(), event.suspendedAt()));

    // 알림은 best-effort다 — 실패해도 이 핸들러를 throw로 끝내지 않는다. throw하면 이 outbox 행이
    // 재드레인되어 위 Integration Event가 중복 적재되기 때문이다(수신 측이 멱등이라 무해하지만
    // 불필요한 증폭을 피한다).
    try {
        notificationService.sendEmail(/* ... */);
    } catch (Exception e) {
        log.error("정지 알림 발송 실패", e);
    }
}
```

`application/event/`의 EventHandler는 `OutboxWriter`를 직접 사용할 수 있는 유일한 예외다 — Aggregate(`Account`)가 Integration Event를 직접 만들지 않는다. 변환 지점은 항상 이 EventHandler다.

### Step 3 — Card BC가 `OutboxEventHandler`를 구현해 수신한다

```java
// card/application/event/AccountSuspendedIntegrationEventHandler.java
@Component
@RequiredArgsConstructor
public class AccountSuspendedIntegrationEventHandler implements OutboxEventHandler {

    private final SuspendCardsByAccountService suspendCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.suspended.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event = objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        suspendCardsByAccountService.suspend(new SuspendCardsByAccountCommand(event.accountId()));
    }
}
```

`OutboxRelay`(공유 인프라, `outbox/` 패키지)는 `List<OutboxEventHandler> eventHandlers`를 Spring이 자동으로 모아 생성자 주입한다 — **Account BC는 Card BC를 전혀 import하지 않는다.** Card BC가 `@Component`로 `OutboxEventHandler`를 구현해 `eventType()`에 `"account.suspended.v1"`을 반환하기만 하면, `OutboxRelay`가 이벤트 타입 문자열로 자동 라우팅한다. 이것이 nestjs 구현의 `EventHandlerRegistry.register()`(명시적 등록)를 대신하는 Spring 관용구다 — Bean 자동 스캔이 등록을 대신한다.

- `AccountIntegrationEventPayload`는 Card BC가 소유하는 로컬 뷰(record)다 — Account BC의 Integration Event 클래스를 import하지 않고 필요한 `accountId` 필드만 읽는다.
- `SuspendCardsByAccountService.suspend()`는 해당 계좌의 **ACTIVE 카드만** 골라 정지하므로, at-least-once 전달로 같은 이벤트가 재수신돼도 멱등하다.
- `OutboxRelay.processPending()`은 한 번의 호출 안에서 여러 패스로 드레인한다 — `AccountSuspendedEventHandler`가 새로 적재한 `account.suspended.v1` 행도 (Domain Event 처리 직후) 같은 호출에서 이어서 처리되므로, `SuspendAccountService.suspend()`가 반환하는 시점에는 Card BC 반응까지 이미 끝나 있다.

### 관련 코드

- `card/application/adapter/AccountAdapter.java` / `card/infrastructure/AccountAdapterImpl.java` — 패턴 1
- `account/application/integrationevent/AccountSuspendedIntegrationEventV1.java`, `AccountClosedIntegrationEventV1.java` — Account BC가 공개하는 계약
- `account/application/event/AccountSuspendedEventHandler.java`, `AccountClosedEventHandler.java` — Domain Event → Integration Event 변환
- `card/application/event/AccountSuspendedIntegrationEventHandler.java`, `AccountClosedIntegrationEventHandler.java` — 패턴 2 수신부
- `card/interfaces/rest/CardControllerE2ETest.java` — 두 패턴을 실제 HTTP API로 검증하는 E2E 테스트

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기(Adapter) vs 비동기(Integration Event) 선택 기준, Context Map 대응
- [module-pattern.md](module-pattern.md) — Spring이 인터페이스 타입 주입 지점에 구현체를 바인딩하는 메커니즘
- [domain-events.md](domain-events.md) — Domain Event/Outbox/Integration Event, 멱등성
- [directory-structure.md](directory-structure.md) — `notification`이 별도 BC가 아니라 Technical Service인 이유
