# 크로스 도메인 호출 패턴

> BC 간 통신 방식을 **언제 동기로, 언제 비동기로 할지**의 의사결정 원칙은 root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 참조한다. 이 문서는 그중 **동기 호출을 선택했을 때** FastAPI/Python으로 Adapter(ACL)를 실제로 어떻게 구현하는지를, 실제 존재하는 두 Bounded Context(`account`, `card`)를 예로 들어 다룬다.

## 이 저장소의 실제 사례 — Card BC가 Account BC를 동기 조회한다

`examples/`에는 `account`와 `card` 두 Bounded Context가 있다. 카드를 발급하려면(`POST /cards`) 연결할 계좌가 존재하고 활성 상태인지를 **같은 요청 안에서 즉시** 확인해야 하므로 동기 Adapter 패턴을 쓴다 — 비동기(Outbox/Integration Event) 방식은 계좌 정지·해지가 카드에 전파되는 반대 방향 흐름에 쓰인다([domain-events.md](domain-events.md)의 "Domain Event vs Integration Event" 참조).

## 원칙

1. **Application Handler는 Adapter 인터페이스를 통해서만 다른 도메인을 호출**한다. 다른 도메인의 Repository나 Handler를 직접 import하지 않는다.
2. **Adapter 인터페이스는 호출하는 쪽의 `application/adapter/`에 ABC로 정의**한다 — Repository/Technical Service와 동일한 위치 원칙([layer-architecture.md](layer-architecture.md)의 "Technical Service 인터페이스" 참조).
3. **Adapter 구현체는 호출하는 쪽의 `infrastructure/`에 배치**하고, 대상 도메인의 실제 진입점(이 저장소에서는 대상 도메인이 공개한 읽기 인터페이스, 예: `AccountQuery`)을 호출한다.
4. Adapter 인터페이스는 **호출하는 쪽이 필요로 하는 형태**로만 메서드를 정의한다 — 대상 도메인의 전체 API나 내부 enum을 노출하지 않는다.
5. 조회 메서드 네이밍은 이 저장소의 실제 Repository 컨벤션(`find_by_id` / `find_all` — [repository-pattern.md](repository-pattern.md)의 "알려진 격차" 참조)을 그대로 따른다.

## 실제 구현 — Card BC에서 Account 활성 여부 확인

```
[Card 도메인]                                       [Account 도메인]
  application/
    adapter/
      account_adapter.py       ← AccountAdapter(ABC) + AccountView
    command/
      issue_card_handler.py    (AccountAdapter 주입)
  domain/
    repository.py               ← AccountQuery(ABC) — Card가 호출하는 대상
  infrastructure/
    account_adapter_impl.py    ← AccountAdapterImpl
```

**1단계 — 호출하는 쪽(Card)의 `application/adapter/`에 ABC 정의**

```python
# src/card/application/adapter/account_adapter.py — 실제 코드
@dataclass(frozen=True)
class AccountView:
    account_id: str
    active: bool


class AccountAdapter(ABC):
    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
```

- `AccountView`는 Account BC의 내부 모델(`Account` Aggregate, `AccountStatus` enum)을 그대로 노출하지 않는 **Card 전용 DTO**다 — `active: bool` 하나로 번역해, Account BC가 상태를 늘리거나 이름을 바꿔도 `AccountAdapter`의 계약은 유지된다.
- 이 ABC는 `NotificationService`(`application/service/notification_service.py`)와 같은 위치 원칙을 따른다: 인터페이스는 Application에, 구현은 Infrastructure에.

**2단계 — Infrastructure에 구현체 작성, Account BC가 공개한 읽기 인터페이스(`AccountQuery`)를 호출**

같은 프로세스 안에 Account BC가 있는 모놀리스 구조이므로(이 저장소의 현재 배포 형태), Adapter 구현체는 Account BC의 CQRS 읽기 인터페이스 `AccountQuery`(`src/account/domain/repository.py`, 쓰기 메서드가 없는 읽기 전용 ABC)를 직접 호출하는 in-process 구현이다. Account의 Repository 구현체나 도메인 객체를 직접 참조하지 않는다.

```python
# src/card/infrastructure/account_adapter_impl.py — 실제 코드
class AccountAdapterImpl(AccountAdapter):

    def __init__(self, account_query: AccountQuery) -> None:
        self._account_query = account_query

    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None:
        account = await self._account_query.find_by_id(account_id, owner_id)
        # 상류의 "계좌 없음"(None)을 그대로 Card 도메인의 None 신호로 전달한다 (오염 방지).
        if account is None:
            return None
        return AccountView(account_id=account.account_id, active=account.status == AccountStatus.ACTIVE)
```

Account BC가 별도 서비스로 분리된 이후라면, 같은 `AccountAdapter` ABC를 구현하는 `HttpAccountAdapter`(내부 HTTP 클라이언트 호출)로 교체할 수 있다 — **호출하는 쪽(Card)의 코드는 한 줄도 바뀌지 않는다.** 이것이 Adapter 패턴이 ACL(Anti-Corruption Layer) 역할을 하는 지점이다.

**3단계 — Application Handler에서 Adapter 사용**

```python
# src/card/application/command/issue_card_handler.py — 실제 코드
class IssueCardHandler:

    def __init__(self, repo: CardRepository, account_adapter: AccountAdapter) -> None:
        self._repo = repo
        self._account_adapter = account_adapter

    async def execute(self, cmd: IssueCardCommand) -> Card:
        # 동기 Adapter(ACL)로 연결 계좌를 조회 — 응답(발급 가부)에 필요하므로 동기 호출.
        account = await self._account_adapter.find_account(cmd.account_id, cmd.requester_id)
        if account is None:
            raise LinkedAccountNotFoundError()
        if not account.active:
            raise CardIssueRequiresActiveAccountError()

        card = Card.issue(account_id=cmd.account_id, owner_id=cmd.requester_id, brand=cmd.brand)
        await self._repo.save(card)
        return card
```

**4단계 — `Depends` 팩토리로 바인딩**

FastAPI에는 NestJS의 `{ provide: AccountAdapter, useClass: AccountAdapterImpl }` 같은 모듈 선언이 없다. 바인딩은 라우터의 `Depends` 팩토리 함수 하나로 대체된다 — [module-pattern.md](module-pattern.md) 참조.

```python
# src/card/interface/rest/card_router.py — 실제 코드
def _account_query(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


def _account_adapter(account_query: AccountQuery = Depends(_account_query)) -> AccountAdapter:
    return AccountAdapterImpl(account_query)


@router.post("", status_code=201, response_model=IssueCardResponse)
async def issue_card(
    body: IssueCardRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: CardRepository = Depends(_repo),
    account_adapter: AccountAdapter = Depends(_account_adapter),
) -> IssueCardResponse:
    card = await IssueCardHandler(repo, account_adapter).execute(
        IssueCardCommand(requester_id=current_user.user_id, account_id=body.account_id, brand=body.brand)
    )
    ...
```

`SqlAlchemyAccountRepository`는 `AccountRepository(AccountQuery, ABC)`를 만족하는 구현체이므로 `_account_query()`처럼 `AccountQuery` 타입으로 좁혀서 주입하면, 이 조회 경로에서 실수로 Account의 쓰기 메서드(`save()` 등)를 호출할 수 없다 — [cqrs-pattern.md](cqrs-pattern.md) 참조.

## 반대 방향(Account → Card) — 왜 여기서는 동기 Adapter를 쓰지 않는가

계좌 정지·해지가 카드에 영향을 주는 흐름은 반대다: Account가 이벤트의 **발행자**이고 Card가 **구독자**다. 이 경우 Account가 Card의 존재를 알 필요가 없어야 하므로(의존 방향이 뒤집히면 결합이 생긴다) 동기 Adapter가 아니라 Outbox 기반 Integration Event(`account.suspended.v1`/`account.closed.v1`)를 쓴다 — 전체 흐름과 실제 코드는 [domain-events.md](domain-events.md#domain-event-vs-integration-event)에 정리되어 있다.

## 왜 직접 import하지 않는가

- **의존성 역전**: `IssueCardHandler`가 `src.account...`의 구체 클래스를 import하면 Card 도메인의 Application 레이어가 Account 도메인의 내부 구조에 결합된다. `AccountAdapter`(ABC)에만 의존하면 결합이 인터페이스 수준으로 제한된다.
- **테스트 격리**: [testing.md](testing.md)의 Application 단위 테스트 패턴대로 `AccountAdapter`를 `unittest.mock.AsyncMock`으로 교체하면 Account BC/DB 없이 `IssueCardHandler`만 검증할 수 있다.
- **점진적 분리에 대응**: In-process 구현에서 HTTP 구현으로 교체할 때 Adapter 구현체(`infrastructure/`)만 바뀌고 Application/Domain 코드는 변경되지 않는다 — 모놀리스에서 서비스 분리로 가는 경로를 Adapter가 흡수한다.

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준, ACL 원칙 (root)
- [domain-events.md](domain-events.md) — 반대 방향(Account → Card)의 비동기 Integration Event 흐름
- [layer-architecture.md](layer-architecture.md) — Technical Service 인터페이스 배치 원칙
- [repository-pattern.md](repository-pattern.md) — `find_by_id`/`find_all` 네이밍 컨벤션과 root와의 격차
- [cqrs-pattern.md](cqrs-pattern.md) — `AccountQuery`/`CardQuery` 읽기 전용 인터페이스 분리
- [module-pattern.md](module-pattern.md) — `Depends` 기반 바인딩
- [testing.md](testing.md) — Adapter mock을 이용한 Application 단위 테스트
