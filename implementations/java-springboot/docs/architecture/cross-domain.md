# 크로스 도메인 호출 패턴 (Spring Boot)

> 언제 동기(Adapter) vs 비동기(Integration Event)를 선택할지의 원칙은 루트 [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 참고한다. 이 문서는 그중 **동기 Adapter 패턴**을 Spring으로 구현하는 방법만 다룬다.

## 이 저장소의 현재 상태 — 예시가 성립하지 않는 이유

`examples/`는 `account`(Aggregate)와 `notification`(Technical Service, [directory-structure.md](directory-structure.md) 참고)만 있는 **단일 Bounded Context** 구조다. `notification`은 별도 BC가 아니라 `account`가 사용하는 기술 서비스이므로, 실제 코드에는 "BC 간" 호출 예시가 없다. 아래는 **가상의 두 번째 BC(User)를 도입한다면**이라는 전제로 작성한 예시다 — `examples/`에 실재하지 않는 코드임을 분명히 한다.

---

## 원칙

1. **Application Service는 다른 BC의 Service/Repository를 직접 주입받지 않는다.** 대신 자신의 `application/adapter/`에 정의한 인터페이스를 통해서만 호출한다.
2. **Adapter 인터페이스는 호출하는 쪽(Account BC)의 `application/adapter/`에** 정의한다 — 호출받는 쪽(User BC)이 아니다. 필요한 형태를 요구하는 쪽이 계약을 정의한다(Repository 패턴과 동일한 의존성 역전).
3. **Adapter 구현체는 호출하는 쪽(Account BC)의 `infrastructure/`에** 두고, User BC가 `@Bean`/`@Component`로 노출한 Service를 주입받아 위임한다.
4. **Adapter를 통해 다른 BC의 쓰기 메서드를 호출하지 않는다.** 조회(ACL)만 한다 — 상태 변경이 필요하면 Integration Event로 전환한다([cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참고).

---

## 예시: Account BC가 (가상의) User BC를 호출

전제: `com.example.accountservice.user` 패키지에 별도 Bounded Context(User)가 이미 존재하고, `UserQueryService`를 공개 API로 노출한다고 가정한다.

### Step 1 — Account BC의 `application/adapter/`에 인터페이스 정의

```java
// account/application/adapter/UserAdapter.java — 인터페이스 (호출하는 쪽이 소유)
package com.example.accountservice.account.application.adapter;

import java.util.Optional;

public interface UserAdapter {

    /**
     * accountId 소유자의 표시 이름을 조회한다.
     * User BC의 내부 구조(Entity, Repository)는 이 메서드 시그니처 뒤에 완전히 숨겨진다.
     */
    Optional<UserSummary> findUser(String ownerId);

    record UserSummary(String userId, String name, String email) {}
}
```

- 인터페이스는 **Account BC가 필요로 하는 형태**로 좁게 정의한다. User BC의 전체 API를 노출하지 않는다.
- `Optional<UserSummary>`처럼 반환 타입 자체를 이 인터페이스가 소유한 record로 정의해, User BC의 내부 DTO/Entity 타입이 Account BC로 새어 들어오지 않게 한다 — 이것이 Adapter가 ACL(Anticorruption Layer) 역할을 하는 지점이다.

### Step 2 — Account BC의 `infrastructure/`에 구현체 작성

```java
// account/infrastructure/UserAdapterImpl.java — 구현체 (호출하는 쪽이 소유)
package com.example.accountservice.account.infrastructure;

import com.example.accountservice.account.application.adapter.UserAdapter;
import com.example.accountservice.user.application.UserQueryService; // (가상) User BC가 노출하는 Service
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserAdapterImpl implements UserAdapter {

    private final UserQueryService userQueryService;

    public UserAdapterImpl(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Override
    public Optional<UserSummary> findUser(String ownerId) {
        return userQueryService.findByUserId(ownerId)
                .map(user -> new UserSummary(user.getUserId(), user.getName(), user.getEmail()));
    }
}
```

- **User BC의 실제 Service(`UserQueryService`)를 주입받는 것은 Adapter 구현체(`infrastructure/`)뿐이다** — `UserQueryService`는 여전히 Spring 빈이므로 `@Component`가 생성자로 주입받는 데 별도의 DI 설정이 필요 없다(NestJS처럼 모듈 `exports`/`imports` 선언이 없어도 같은 `ApplicationContext` 안이면 타입으로 자동 바인딩된다 — [module-pattern.md](module-pattern.md) 참고).
- `UserQueryService`가 반환하는 User BC 내부 타입(`User` 도메인 객체 등)을 여기서 `UserSummary`로 변환한다 — 변환 지점이 Adapter 구현체 안에 갇혀 있어야, User BC의 내부 모델이 바뀌어도 Account BC의 Application 레이어는 영향받지 않는다.

### Step 3 — Account BC의 Application Service에서 Adapter 사용

```java
// account/application/query/GetAccountService.java — Adapter 사용 예 (가상 확장)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {

    private final AccountRepository accountRepository;
    private final UserAdapter userAdapter;   // 구체 타입(UserAdapterImpl)이 아니라 인터페이스에 의존

    public GetAccountWithOwnerResult getAccountWithOwner(String accountId, String requesterId) {
        Account account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));

        UserAdapter.UserSummary owner = userAdapter.findUser(account.getOwnerId())
                .orElse(null);   // User BC 조회 실패가 Account 조회 자체를 막지 않도록 완화 처리

        return new GetAccountWithOwnerResult(account.getAccountId(), account.getBalance(), owner);
    }
}
```

- `GetAccountService`는 `UserAdapter` **인터페이스**에만 의존한다 — `UserAdapterImpl`, 나아가 `UserQueryService`의 존재 자체를 모른다.
- Spring이 `UserAdapter` 타입의 주입 지점에 classpath상 유일한 구현체(`UserAdapterImpl`)를 자동 바인딩한다 — `AccountRepository`/`AccountRepositoryImpl` 관계와 완전히 동일한 메커니즘([layer-architecture.md](layer-architecture.md) 참고).
- 테스트 시 `UserAdapter`를 Mockito로 mock하면 User BC 없이 `GetAccountService`를 단위 테스트할 수 있다.

---

## 왜 인터페이스가 필요한가 — `@Component`만으로는 부족한 이유

"어차피 같은 프로세스, 같은 `ApplicationContext`인데 `UserQueryService`를 직접 주입받으면 안 되는가?"라는 질문이 자연스럽게 나올 수 있다. 안 되는 이유:

- **의존 방향 오염**: Account BC의 Application 레이어가 User BC의 구체 타입을 import하면, User BC 내부 구조 변경이 Account BC의 컴파일을 깨뜨린다. `UserAdapter` 인터페이스가 이 결합을 끊는다.
- **불필요한 노출 차단**: `UserQueryService`가 갖는 20개 메서드 중 Account BC가 필요한 것은 1개(`findByUserId`)뿐일 수 있다. Adapter 인터페이스는 그 1개만 노출한다.
- **테스트 격리**: `UserAdapter`를 mock하면 User BC(및 그 Repository, DB 접근)를 부팅하지 않고도 Account BC 단위 테스트가 가능하다 — `UserQueryService`를 직접 주입받았다면 mock 대상이 User BC 내부 구현에 종속된다.

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기(Adapter) vs 비동기(Integration Event) 선택 기준, Context Map 대응
- [module-pattern.md](module-pattern.md) — Spring이 인터페이스 타입 주입 지점에 구현체를 바인딩하는 메커니즘
- [domain-events.md](domain-events.md) — 상태 변경이 필요한 크로스 BC 통신(Integration Event, Outbox)
- [directory-structure.md](directory-structure.md) — `notification`이 별도 BC가 아니라 Technical Service인 이유
