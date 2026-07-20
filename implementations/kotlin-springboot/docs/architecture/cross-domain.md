# 크로스 도메인 호출 패턴 — Kotlin Spring Boot

> 원칙과 선택 기준(동기 Adapter vs 비동기 Integration Event)은 [root cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참조. 이 문서는 **동기 Adapter 패턴과 비동기 Integration Event 패턴의 구체적인 Kotlin/Spring 구현**을 다룬다.

## 이 저장소의 현재 상태 — Account ↔ Card 두 BC의 실제 코드

`examples/`는 Account Bounded Context와 Card Bounded Context 두 개를 구현한다(`notification/`은 별도 BC가 아니라 Technical Service — [directory-structure.md](directory-structure.md) 참조). 두 BC는 서로 다른 방향의 통신 패턴을 사용한다.

- **동기 Adapter(ACL)**: 카드 발급 시 Card BC가 Account BC를 즉시 조회해야 하므로(연결 계좌의 존재·활성 여부가 응답에 필요) `card/application/adapter/AccountAdapter.kt` + `card/infrastructure/AccountAdapterImpl.kt`가 Account BC의 읽기 포트(`account/application/query/AccountQuery`)를 동기 호출한다.
- **비동기 Integration Event**: Account가 정지/해지되어도 Card BC가 그 결과(연결 카드 SUSPENDED/CANCELLED 전환)를 응답에 즉시 반영할 필요는 없으므로, Account BC가 Outbox를 통해 `account.suspended.v1`/`account.closed.v1` Integration Event를 발행하고 Card BC가 비동기로 반응한다.

아래는 실제 코드를 기준으로 두 패턴을 각각 설명한다.

## 원칙 — NestJS와 다른 지점

NestJS는 Repository와 마찬가지로 Adapter 인터페이스를 `abstract class`로 정의한다(NestJS DI 컨테이너가 순수 `interface`를 런타임 토큰으로 쓸 수 없기 때문). **Kotlin/Spring은 Repository 패턴에서와 동일한 이유로 `interface` 자체가 DI 토큰이 된다** — Spring이 클래스패스에서 해당 인터페이스의 유일한 구현체를 찾아 자동 바인딩하므로 `abstract class` 우회가 필요 없다 ([layer-architecture.md](layer-architecture.md) "Domain 레이어" 절 참조).

1. **Application Service는 Adapter 인터페이스를 통해서만 외부 BC를 호출**한다 — 외부 BC의 Service/Repository를 직접 주입하지 않는다.
2. **Adapter 인터페이스는 호출하는 쪽(Card BC)의 `application/adapter/`에 Kotlin `interface`로 정의**한다.
3. **Adapter 구현체는 호출하는 쪽의 `infrastructure/`에 `@Component`로 배치**하고, 외부 BC 모듈이 공개(exports에 해당하는 public 빈)한 Service/Query를 생성자로 주입받는다.
4. Adapter는 **조회 전용** — 외부 BC의 상태 변경 메서드를 호출하지 않는다. 쓰기가 필요하면 Integration Event로 전환한다([root 문서](../../../../docs/architecture/cross-domain-communication.md) 참조).

## 예시 1 — Card BC에서 Account BC를 동기 조회 (실제 코드)

```kotlin
// card/application/adapter/AccountAdapter.kt — 인터페이스
package com.example.accountservice.card.application.adapter

interface AccountAdapter {
    fun findAccount(accountId: String, ownerId: String): AccountView?
}

data class AccountView(
    val accountId: String,
    val active: Boolean,
)
```

`interface`이지 `abstract class`가 아니다 — Kotlin에서는 여기에 아무 프레임워크 의존성도 필요 없다. 반환 타입이 `AccountView?`(nullable)인 것도 root의 "찾지 못함" 표현을 Kotlin의 null-safety로 옮긴 것 — 호출자가 `?:`로 처리하지 않으면 컴파일이 되지 않는다([layer-architecture.md](layer-architecture.md)에서 Repository에 적용한 것과 동일한 관용구). `AccountView`가 `active: Boolean`만 노출하고 Account BC의 `AccountStatus` enum(`ACTIVE`/`SUSPENDED`/`CLOSED`) 자체를 그대로 넘기지 않는 것이 핵심 — Account가 상태를 추가/변경해도 Card는 "활성 여부"라는 자신에게 필요한 질문에만 계속 답할 수 있다.

```kotlin
// card/infrastructure/AccountAdapterImpl.kt — 구현체 (ACL)
package com.example.accountservice.card.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.AccountView
import org.springframework.stereotype.Component

@Component
class AccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {

    override fun findAccount(accountId: String, ownerId: String): AccountView? =
        accountQuery.findByAccountIdAndOwnerId(accountId, ownerId)?.let { account ->
            AccountView(accountId = account.accountId, active = account.status == AccountStatus.ACTIVE)
        }
}
```

- `accountQuery: AccountQuery`는 **Account BC가 공개하는 읽기 포트**다(`account/application/query/AccountQuery`) — Account BC의 쓰기 모델(`AccountRepository`)이나 `domain/`(Aggregate)에는 접근하지 않는다. 이것이 Anticorruption Layer(ACL) 역할이다: Account BC의 내부 모델(`Account` 도메인 클래스, `AccountStatus` enum)이 바뀌어도 `AccountAdapterImpl`의 매핑 로직만 고치면 되고, Card BC의 `AccountView`는 영향받지 않는다.
- `.let { }` 스코프 함수로 null-safety를 유지한 채 매핑한다 — Account를 찾지 못하면(`AccountQuery`가 계좌 없음을 `null`로 표현) `null`을 그대로 전파한다. Account BC의 예외 타입(`AccountNotFoundException`)이 Card 레이어로 누수되지 않는다.

```kotlin
// card/application/command/IssueCardService.kt — Adapter를 통해 호출
@Service
class IssueCardService(
    private val cardRepository: CardRepository,
    private val accountAdapter: AccountAdapter,
) {
    fun issue(command: IssueCardCommand): IssueCardResult {
        val account = accountAdapter.findAccount(command.accountId, command.requesterId)
            ?: throw LinkedAccountNotFoundException()
        if (!account.active) throw CardIssueRequiresActiveAccountException()

        val card = Card.issue(accountId = command.accountId, ownerId = command.requesterId, brand = command.brand)
        cardRepository.save(card)
        return IssueCardResult(/* ... */)
    }
}
```

`account?.active`처럼 Adapter 호출 결과에도 그대로 null-safety가 이어진다 — "계좌 없음"과 "계좌는 있지만 비활성"을 서로 다른 도메인 예외(`LinkedAccountNotFoundException` vs `CardIssueRequiresActiveAccountException`)로 승격시켜 각각 다른 HTTP 상태(404/400)로 응답한다.

## Spring 빈 등록 — 패키지 스캔이면 충분

NestJS는 `CardModule`의 `providers` 배열에 `{ provide: AccountAdapter, useClass: AccountAdapterImpl }`를 명시적으로 등록해야 한다. Kotlin/Spring은 `AccountAdapterImpl`이 `@Component`이고 `com.example.accountservice` 하위 패키지에 있으면 **컴포넌트 스캔이 자동으로 등록**하고, `AccountAdapter` 타입을 요구하는 생성자가 있으면 유일한 구현체를 자동 주입한다 — 별도의 모듈 등록 파일이 없다([module-pattern.md](module-pattern.md) 참조).

주의할 점은 구현체가 두 개 이상 생기는 경우다(예: 테스트용 Fake와 실제 구현체가 같은 패키지에 있을 때) — 이때는 `@Primary` 또는 `@Qualifier`로 명시해야 한다. 테스트에서는 보통 `@MockBean`/MockK로 `AccountAdapter`를 목(mock)하므로 프로덕션 코드에서 이 문제가 발생할 일은 드물다.

## 예시 2 — Account → Card 비동기 Integration Event (실제 코드)

카드 발급과 반대 방향 통신은 동기 Adapter가 아니라 Outbox 기반 Integration Event를 쓴다 — Account가 정지/해지되는 순간 Card BC의 반응(카드 정지/해지)이 완료될 때까지 Account의 커맨드 응답을 붙잡아 둘 이유가 없고, Account가 Card의 존재 자체를 몰라야 하기 때문이다([cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참조).

**1) Account 쪽 — 내부 Domain Event를 외부 공개 계약으로 변환**

```kotlin
// account/application/integrationevent/AccountSuspendedIntegrationEventV1.kt
data class AccountSuspendedIntegrationEventV1(
    val accountId: String,
    val suspendedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME
    companion object { const val EVENT_NAME = "account.suspended.v1" }
}
```

```kotlin
// account/application/event/AccountSuspendedEventHandler.kt
@Component
class AccountSuspendedEventHandler(
    private val notificationService: NotificationService,
    private val outboxWriter: OutboxWriter,
) {
    fun handle(event: AccountSuspendedEvent) {
        // 외부 BC(Card 등)에 알리는 Integration Event를 같은 트랜잭션에 적재한다.
        outboxWriter.saveAll(listOf(AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt.toString())))
        notificationService.sendEmail(/* ... */)
    }
}
```

내부 Domain Event(`AccountSuspendedEvent`, 클래스명이 그대로 Outbox `eventType`이 됨)와 외부 공개 계약(`AccountSuspendedIntegrationEventV1`, 버전이 명시된 `eventName`이 `eventType`이 됨)을 분리한다 — Account 내부 리팩터링이 외부 BC와의 계약을 깨지 않게 하기 위함이다. 변환 지점은 항상 `application/event/`의 EventHandler다(Aggregate가 Integration Event를 직접 만들지 않는다).

**2) 공유 인프라(outbox/) — eventType 결정 + 라우팅**

```kotlin
// outbox/OutboxEvent.kt
this.eventType = (event as? IntegrationEventContract)?.eventName ?: (event::class.simpleName ?: "Unknown")
```

```kotlin
// outbox/EventHandlerRegistry.kt — eventType → 핸들러 Map + 생성자 주입 (2026-07 async 전환 이후 실제 코드)
@Component
class EventHandlerRegistry(
    /* ...Account Domain Event 핸들러들... */
    private val cardIntegrationEventController: CardIntegrationEventController,
) {
    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> =
        mapOf(
            /* ...AccountCreatedEvent 등... */
            AccountSuspendedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountSuspendedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountSuspended(event.accountId)
            },
            AccountClosedIntegrationEventV1.EVENT_NAME to { _, payload -> /* ...onAccountClosed... */ },
        )

    fun dispatch(eventType: String, eventId: String, payload: String) {
        val handler = handlers[eventType]
        if (handler == null) {
            logger.warn("알 수 없는 이벤트 타입: {}", eventType)
            return
        }
        handler(eventId, payload)
    }
}
```

`outbox/`는 어느 BC에도 속하지 않는 공유 인프라이므로, `EventHandlerRegistry`가 Account의 Domain Event 핸들러와 Card의 Integration Event 수신부를 둘 다 생성자로 주입받아 참조하는 것은 원칙 위반이 아니다 — 금지되는 것은 **Account가 Card를 참조하는 것**뿐이다(`account/` 패키지 안 어떤 파일도 `card/`를 import하지 않는다). kotlin-springboot는 java-springboot처럼 애노테이션 기반 자동 discovery를 쓰지 않고, 이런 명시적 생성자 주입 + `Map` 리터럴 방식을 쓴다 — 어떤 eventType이 어떤 핸들러로 가는지 이 한 파일만 보면 전부 알 수 있다는 것이 트레이드오프다(2026-07 async 전환 전에는 `OutboxRelay`가 같은 역할을 `when(eventType)` 하드코딩 분기로 했다 — `EventHandlerRegistry`는 그 분기를 미리 구성한 `Map` 조회로 대체했을 뿐, 생성자 주입 방식 자체는 동일하다).

`EventHandlerRegistry`는 직접 `@Scheduled`나 SQS를 다루지 않는다 — `OutboxConsumer`가 SQS에서 수신한 메시지를 이 레지스트리의 `dispatch()`로 넘길 뿐이다. 그리고 **Domain Event → Integration Event 변환 → 외부 BC(Card) 수신은 더 이상 한 트랜잭션·한 호출 안에서 완결되지 않는다**([domain-events.md](domain-events.md)의 "비동기 재드레인 경계가 바뀌었다" 절 참조) — `AccountSuspendedEventHandler`가 위 1)에서 새 Outbox 행(`account.suspended.v1`)을 적재하면, 그 행은 다음 `OutboxPoller` tick(최대 1초 후)에 SQS로 발행되고, `OutboxConsumer`가 그것을 다시 수신해 `EventHandlerRegistry.dispatch()`를 거쳐서야 `CardIntegrationEventController`까지 전달된다. 즉 Account 커맨드 처리(원래 트랜잭션)가 끝난 뒤 최소 두 번의 Poller/Consumer 왕복(수백 ms~수 초)을 거쳐야 Card BC가 반응한다.

**3) Card 쪽 — 수신 후 자기 유스케이스만 호출**

```kotlin
// card/interfaces/integrationevent/CardIntegrationEventController.kt
@Component
class CardIntegrationEventController(
    private val suspendCardsByAccountService: SuspendCardsByAccountService,
    private val cancelCardsByAccountService: CancelCardsByAccountService,
) {
    fun onAccountSuspended(accountId: String) {
        suspendCardsByAccountService.suspend(accountId)
    }
    fun onAccountClosed(accountId: String) {
        cancelCardsByAccountService.cancel(accountId)
    }
}
```

HTTP `CardController`와 마찬가지로 `interfaces/`에 위치하는 입력 어댑터다. Account의 Integration Event 페이로드에서 `accountId`만 받아 자기 도메인의 Command Service를 호출할 뿐, Account BC의 클래스를 참조하지 않는다. `SuspendCardsByAccountService`/`CancelCardsByAccountService`는 ACTIVE(또는 ACTIVE·SUSPENDED) 카드만 골라 상태를 바꾸므로, 같은 이벤트가 재수신되어도(at-least-once 전달) 멱등하게 아무 일도 하지 않는다.

## 원칙 요약

- **인터페이스는 Kotlin `interface`** — NestJS의 `abstract class` 우회가 필요 없다.
- **"찾지 못함"은 `T?` + `?:`** — `Optional`이나 별도 null 체크 없이 표현한다.
- **동기 Adapter는 조회 전용** — Adapter로 외부 BC의 쓰기 메서드를 호출하지 않는다. 쓰기가 필요하면 Integration Event.
- **매핑은 Adapter 구현체 책임** — 외부 BC의 응답 모델을 그대로 반환하지 않고 호출하는 쪽이 필요한 형태(`AccountView`)로 변환한다.
- **등록은 컴포넌트 스캔에 위임** — `@Component`만 붙이면 되고, 별도의 명시적 바인딩 파일이 필요 없다.
- **Integration Event 변환은 항상 `application/event/`의 EventHandler**가 담당 — Aggregate가 직접 만들지 않는다.
- **`outbox/`는 공유 인프라** — 여러 BC의 핸들러를 참조해도 되지만, 발행 BC(Account)가 수신 BC(Card)를 참조해서는 안 된다.
- **수신 측은 멱등하게** — at-least-once 전달을 전제로, 이미 반영된 상태 변경은 재수신 시 조용히 무시한다.

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준 (root, 프레임워크 무관)
- [domain-events.md](../../../../docs/architecture/domain-events.md) — Outbox 패턴, Domain Event/Integration Event 분리
- [module-pattern.md](module-pattern.md) — 컴포넌트 스캔과 패키지 간 의존
- [layer-architecture.md](layer-architecture.md) — `interface`가 DI 토큰이 되는 이유, null-safety
- [domain-service.md](../../../../docs/architecture/domain-service.md) — Technical Service(암복호화 등)와의 구분
