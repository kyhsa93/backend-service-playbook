# 크로스 도메인 호출 패턴

> BC 간 통신 방식을 **언제 동기로, 언제 비동기로 할지**의 의사결정 원칙은 root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 참조한다. 이 문서는 그중 **동기 호출을 선택했을 때** FastAPI/Python으로 Adapter(ACL)를 실제로 어떻게 구현하는지를 다룬다.

## 이 저장소의 현재 상태 — 단일 도메인이라 실제 사례가 없다

`examples/`에는 Account 도메인 하나뿐이라 크로스 도메인 호출이 실제로 존재하지 않는다. 아래는 **두 번째 Bounded Context(예: User)가 추가된다고 가정한 예시**이며, 실제 코드가 아니라 설계 가이드임을 분명히 한다.

## 원칙

1. **Application Handler는 Adapter 인터페이스를 통해서만 다른 도메인을 호출**한다. 다른 도메인의 Repository나 Handler를 직접 import하지 않는다.
2. **Adapter 인터페이스는 호출하는 쪽의 `application/adapter/`에 ABC로 정의**한다 — Repository/Technical Service와 동일한 위치 원칙([layer-architecture.md](layer-architecture.md)의 "Technical Service 인터페이스" 참조).
3. **Adapter 구현체는 호출하는 쪽의 `infrastructure/`에 배치**하고, 대상 도메인의 실제 진입점(Handler 혹은 Repository)을 호출한다.
4. Adapter 인터페이스는 **호출하는 쪽이 필요로 하는 형태**로만 메서드를 정의한다 — 대상 도메인의 전체 API를 노출하지 않는다.
5. 조회 메서드 네이밍은 이 저장소의 실제 Repository 컨벤션(`find_by_id` / `find_all` — [repository-pattern.md](repository-pattern.md)의 "알려진 격차" 참조)을 그대로 따른다. root/NestJS가 쓰는 `find<Noun>s` 단일 메서드 패턴이 아니라, 단건은 `find_by_id`, 목록은 `find_all`로 분리한 이 저장소의 기존 스타일과 일관성을 유지한다.

## 예시 — Account 도메인에서 사용자 요약 정보 조회

가상의 User BC가 있고, Account BC가 계좌 생성 시 사용자 이메일 유효성을 확인해야 한다고 가정한다.

```
[Account 도메인]                                    [User 도메인 — 가상]
  application/
    adapter/
      user_adapter.py         ← UserAdapter(ABC)
    command/
      create_account_handler.py  (UserAdapter 주입)
  infrastructure/
    user_adapter_impl.py      ← HttpUserAdapter 또는 InProcessUserAdapter
```

**1단계 — 호출하는 쪽(Account)의 `application/adapter/`에 ABC 정의**

```python
# src/account/application/adapter/user_adapter.py
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class UserSummary:
    user_id: str
    email: str
    is_active: bool


class UserAdapter(ABC):
    @abstractmethod
    async def find_by_id(self, user_id: str) -> UserSummary | None: ...
```

- `UserSummary`는 User BC의 내부 모델(`User` Aggregate)을 그대로 노출하지 않는 **호출하는 쪽 전용 DTO**다 — User BC의 내부 구조가 바뀌어도 `UserAdapter`의 계약은 유지된다.
- 이 ABC는 `NotificationService`(`application/service/notification_service.py`)와 같은 위치 원칙을 따른다: 인터페이스는 Application에, 구현은 Infrastructure에.

**2단계 — Infrastructure에 구현체 작성**

같은 프로세스 안에 User BC가 있는 모놀리스 구조라면(이 저장소의 현재 배포 형태), Adapter 구현체는 User BC의 Handler를 직접 호출하는 in-process 구현으로 시작하는 것이 가장 단순하다.

```python
# src/account/infrastructure/user_adapter_impl.py
from src.account.application.adapter.user_adapter import UserAdapter, UserSummary
from src.user.application.query.get_user_handler import GetUserHandler, GetUserQuery  # 가상의 User BC


class InProcessUserAdapter(UserAdapter):
    def __init__(self, get_user_handler: GetUserHandler) -> None:
        self._get_user_handler = get_user_handler

    async def find_by_id(self, user_id: str) -> UserSummary | None:
        result = await self._get_user_handler.execute(GetUserQuery(user_id=user_id))
        if result is None:
            return None
        return UserSummary(user_id=result.user_id, email=result.email, is_active=result.is_active)
```

User BC가 별도 서비스로 분리된 이후라면, 같은 ABC를 구현하는 `HttpUserAdapter`(내부 HTTP 클라이언트 호출)로 교체할 수 있다 — **호출하는 쪽(Account)의 코드는 한 줄도 바뀌지 않는다.** 이것이 Adapter 패턴이 ACL(Anti-Corruption Layer) 역할을 하는 지점이다.

```python
# src/account/infrastructure/user_adapter_impl.py — 서비스 분리 이후로 교체하는 예시
import httpx

from src.account.application.adapter.user_adapter import UserAdapter, UserSummary


class HttpUserAdapter(UserAdapter):
    def __init__(self, client: httpx.AsyncClient) -> None:
        self._client = client

    async def find_by_id(self, user_id: str) -> UserSummary | None:
        response = await self._client.get(f"/users/{user_id}")
        if response.status_code == 404:
            return None
        body = response.json()
        return UserSummary(user_id=body["userId"], email=body["email"], is_active=body["isActive"])
```

**3단계 — Application Handler에서 Adapter 사용**

```python
# src/account/application/command/create_account_handler.py
class CreateAccountHandler:
    def __init__(self, repo: AccountRepository, user_adapter: UserAdapter) -> None:
        self._repo = repo
        self._user_adapter = user_adapter

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        user = await self._user_adapter.find_by_id(cmd.requester_id)
        if user is None or not user.is_active:
            raise AccountOwnerNotEligibleError(cmd.requester_id)

        account = Account.create(owner_id=cmd.requester_id, currency=cmd.currency, email=cmd.email)
        await self._repo.save(account)
        return account
```

**4단계 — `Depends` 팩토리로 바인딩**

FastAPI에는 NestJS의 `{ provide: UserAdapter, useClass: UserAdapterImpl }` 같은 모듈 선언이 없다. 바인딩은 라우터의 `Depends` 팩토리 함수 하나로 대체된다 — [module-pattern.md](module-pattern.md) 참조.

```python
# src/account/interface/rest/account_router.py
def _user_adapter(session: AsyncSession = Depends(get_session)) -> UserAdapter:
    return InProcessUserAdapter(GetUserHandler(...))  # 또는 HttpUserAdapter(...)


@router.post("", status_code=201, response_model=CreateAccountResponse)
async def create_account(
    body: CreateAccountRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    user_adapter: UserAdapter = Depends(_user_adapter),
) -> CreateAccountResponse:
    account = await CreateAccountHandler(repo, user_adapter).execute(
        CreateAccountCommand(requester_id=x_user_id, currency=body.currency, email=body.email)
    )
    ...
```

## 왜 직접 import하지 않는가

- **의존성 역전**: `CreateAccountHandler`가 `src.user...`의 구체 클래스를 import하면 Account 도메인의 Application 레이어가 User 도메인의 내부 구조에 결합된다. `UserAdapter`(ABC)에만 의존하면 결합이 인터페이스 수준으로 제한된다.
- **테스트 격리**: [testing.md](testing.md)의 Application 단위 테스트 패턴대로 `UserAdapter`를 `unittest.mock.AsyncMock`으로 교체하면 User BC/DB 없이 `CreateAccountHandler`만 검증할 수 있다.
- **점진적 분리에 대응**: In-process 구현에서 HTTP 구현으로 교체할 때 Adapter 구현체(`infrastructure/`)만 바뀌고 Application/Domain 코드는 변경되지 않는다 — 모놀리스에서 서비스 분리로 가는 경로를 Adapter가 흡수한다.

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준, ACL 원칙 (root)
- [layer-architecture.md](layer-architecture.md) — Technical Service 인터페이스 배치 원칙
- [repository-pattern.md](repository-pattern.md) — `find_by_id`/`find_all` 네이밍 컨벤션과 root와의 격차
- [module-pattern.md](module-pattern.md) — `Depends` 기반 바인딩
- [testing.md](testing.md) — Adapter mock을 이용한 Application 단위 테스트
