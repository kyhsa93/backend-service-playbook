# 크로스 도메인 호출 패턴 — Kotlin Spring Boot

> 원칙과 선택 기준(동기 Adapter vs 비동기 Integration Event)은 [root cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참조. 이 문서는 **동기 Adapter 패턴의 구체적인 Kotlin/Spring 구현**만 다룬다.

## 이 저장소의 현재 상태 — 단일 BC라 예시가 없다

`examples/`는 Account Bounded Context 하나만 구현하며, `notification/`은 별도 BC가 아니라 Technical Service([directory-structure.md](directory-structure.md) 참조)다. 따라서 이 문서의 코드는 **실제 코드가 아니라 잘 구성된 가상 예시**다 — Account BC가 가상의 User BC를 호출하는 상황을 가정한다. 두 번째 BC가 실제로 추가되면 이 패턴을 그대로 적용한다.

## 원칙 — NestJS와 다른 지점

NestJS는 Repository와 마찬가지로 Adapter 인터페이스를 `abstract class`로 정의한다(NestJS DI 컨테이너가 순수 `interface`를 런타임 토큰으로 쓸 수 없기 때문). **Kotlin/Spring은 Repository 패턴에서와 동일한 이유로 `interface` 자체가 DI 토큰이 된다** — Spring이 클래스패스에서 해당 인터페이스의 유일한 구현체를 찾아 자동 바인딩하므로 `abstract class` 우회가 필요 없다 ([layer-architecture.md](layer-architecture.md) "Domain 레이어" 절 참조).

1. **Application Service는 Adapter 인터페이스를 통해서만 외부 BC를 호출**한다 — 외부 BC의 Service/Repository를 직접 주입하지 않는다.
2. **Adapter 인터페이스는 호출하는 쪽(Account BC)의 `application/adapter/`에 Kotlin `interface`로 정의**한다.
3. **Adapter 구현체는 호출하는 쪽의 `infrastructure/`에 `@Component`로 배치**하고, 외부 BC 모듈이 공개(exports에 해당하는 public 빈)한 Service를 생성자로 주입받는다.
4. Adapter는 **조회 전용** — 외부 BC의 상태 변경 메서드를 호출하지 않는다. 쓰기가 필요하면 Integration Event로 전환한다([root 문서](../../../../docs/architecture/cross-domain-communication.md) 참조).

## 예시 — Account BC에서 User BC 조회

```kotlin
// account/application/adapter/UserAdapter.kt — 인터페이스 (가상 예시)
package com.example.accountservice.account.application.adapter

interface UserAdapter {
    fun findUser(userId: String): UserSummary?
}

data class UserSummary(
    val userId: String,
    val displayName: String,
    val email: String,
)
```

`interface`이지 `abstract class`가 아니다 — Kotlin에서는 여기에 아무 프레임워크 의존성도 필요 없다. 반환 타입이 `UserSummary?`(nullable)인 것도 root의 "찾지 못함" 표현을 Kotlin의 null-safety로 옮긴 것 — 호출자가 `?:`로 처리하지 않으면 컴파일이 되지 않는다([layer-architecture.md](layer-architecture.md)에서 Repository에 적용한 것과 동일한 관용구).

```kotlin
// account/infrastructure/UserAdapterImpl.kt — 구현체 (가상 예시)
package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.adapter.UserAdapter
import com.example.accountservice.account.application.adapter.UserSummary
import com.example.accountservice.user.application.service.UserService
import org.springframework.stereotype.Component

@Component
class UserAdapterImpl(
    private val userService: UserService,
) : UserAdapter {

    override fun findUser(userId: String): UserSummary? =
        userService.findById(userId)?.let { user ->
            UserSummary(userId = user.id, displayName = user.name, email = user.email)
        }
}
```

- `userService: UserService`는 **User BC가 공개하는 Application Service**다 — User BC의 `domain/`(Aggregate, Repository)에는 접근하지 않는다. 이것이 Anticorruption Layer(ACL) 역할이다: User BC의 내부 모델(`User` 도메인 클래스)이 바뀌어도 `UserAdapterImpl`의 매핑 로직만 고치면 되고, Account BC의 `UserSummary`는 영향받지 않는다.
- `.let { }` 스코프 함수로 null-safety를 유지한 채 매핑한다 — User를 찾지 못하면 `null`을 그대로 전파하고, 호출자가 필요하면 예외로 승격시킨다.

```kotlin
// account/application/query/GetAccountService.kt — Adapter를 통해 호출 (가상 예시, 실제 GetAccountService는 User 조회 없음)
@Service
@Transactional(readOnly = true)
class GetAccountService(
    private val accountRepository: AccountRepository,
    private val userAdapter: UserAdapter,
) {
    fun getAccountWithOwner(accountId: String, requesterId: String): GetAccountWithOwnerResult {
        val account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
            ?: throw AccountNotFoundException(accountId)

        val owner = userAdapter.findUser(account.ownerId)

        return GetAccountWithOwnerResult(
            accountId = account.accountId,
            balance = account.balance.amount,
            ownerName = owner?.displayName,
        )
    }
}
```

`owner?.displayName`처럼 Adapter 호출 결과에도 그대로 null-safety가 이어진다 — User BC 조회가 실패하거나 사용자가 삭제되어도 Account 조회 자체가 죽지 않고, `ownerName`이 nullable 필드로 응답에 반영된다. 이는 root가 강조하는 "외부 BC 장애가 내 BC의 핵심 흐름을 막지 않는다"는 원칙과도 맞닿아 있다.

## Spring 빈 등록 — 패키지 스캔이면 충분

NestJS는 `OrderModule`의 `providers` 배열에 `{ provide: UserAdapter, useClass: UserAdapterImpl }`를 명시적으로 등록해야 한다. Kotlin/Spring은 `UserAdapterImpl`이 `@Component`이고 `com.example.accountservice` 하위 패키지에 있으면 **컴포넌트 스캔이 자동으로 등록**하고, `UserAdapter` 타입을 요구하는 생성자가 있으면 유일한 구현체를 자동 주입한다 — 별도의 모듈 등록 파일이 없다([module-pattern.md](module-pattern.md) 참조).

주의할 점은 구현체가 두 개 이상 생기는 경우다(예: 테스트용 Fake와 실제 구현체가 같은 패키지에 있을 때) — 이때는 `@Primary` 또는 `@Qualifier`로 명시해야 한다. 테스트에서는 보통 `@MockBean`/MockK로 `UserAdapter`를 목(mock)하므로 프로덕션 코드에서 이 문제가 발생할 일은 드물다.

## 원칙 요약

- **인터페이스는 Kotlin `interface`** — NestJS의 `abstract class` 우회가 필요 없다.
- **"찾지 못함"은 `T?` + `?:`** — `Optional`이나 별도 null 체크 없이 표현한다.
- **조회 전용** — Adapter로 외부 BC의 쓰기 메서드를 호출하지 않는다.
- **매핑은 Adapter 구현체 책임** — 외부 BC의 응답 모델을 그대로 반환하지 않고 호출하는 쪽이 필요한 형태(`UserSummary`)로 변환한다.
- **등록은 컴포넌트 스캔에 위임** — `@Component`만 붙이면 되고, 별도의 명시적 바인딩 파일이 필요 없다.

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준 (root, 프레임워크 무관)
- [module-pattern.md](module-pattern.md) — 컴포넌트 스캔과 패키지 간 의존
- [layer-architecture.md](layer-architecture.md) — `interface`가 DI 토큰이 되는 이유, null-safety
- [domain-service.md](../../../../docs/architecture/domain-service.md) — Technical Service(암복호화 등)와의 구분
