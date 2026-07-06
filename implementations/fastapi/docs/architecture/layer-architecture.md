# 레이어 아키텍처

> 프레임워크 무관 원칙: [../../../../docs/architecture/layer-architecture.md](../../../../docs/architecture/layer-architecture.md)

## 의존 방향

```
Interface (APIRouter)  →  Application (Handler)  →  Domain (Aggregate, Repository ABC)
                                                          ↑
                                                   Infrastructure (Repository 구현체, Technical Service 구현체)
```

- 상위 레이어는 하위 레이어에 의존할 수 있지만, 하위 레이어는 상위 레이어에 의존하지 않는다.
- Domain 레이어는 어떤 레이어에도 의존하지 않는다 — `fastapi`, `sqlalchemy`, `aioboto3` import 없음.
- Infrastructure 레이어는 Domain/Application의 ABC를 구현한다 (의존성 역전) — FastAPI에는 전용 DI 컨테이너가 없으므로, `Depends`의 팩토리 함수가 이 바인딩을 담당한다.

이 저장소의 실제 코드로 각 레이어를 살펴본다 (`examples/src/account/`).

---

## Domain 레이어 — `domain/`

비즈니스 규칙의 핵심. 어떤 프레임워크에도 의존하지 않는다.

1. **Aggregate Root** — `account.py`의 `Account`. 일반 클래스(`__init__` + 메서드)로 구현되어 있다 — 상태 변경(`deposit`, `withdraw`, `suspend`, `reactivate`, `close`)은 모두 Aggregate 메서드를 통해서만 일어난다.
2. **Entity** — `transaction.py`의 `Transaction`. `@dataclass(frozen=True)`이지만 `transaction_id`라는 고유 식별자로 동등성이 결정되므로 Entity다.
3. **Value Object** — `money.py`의 `Money`. `@dataclass(frozen=True)`, 식별자 없음, 속성 조합으로 동등성 판단.
4. **Domain Event** — `events.py`의 `AccountCreated`, `MoneyDeposited` 등. 모두 `@dataclass(frozen=True)`, 과거형 이름.
5. **Repository 인터페이스** — `repository.py`의 `AccountRepository(ABC)`. 구현은 `infrastructure/`.

```python
# domain/repository.py — Repository 인터페이스 (ABC)
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountRepository(ABC):
    @abstractmethod
    async def find_by_id(self, account_id: str, owner_id: str) -> Account | None: ...

    @abstractmethod
    async def save(self, account: Account) -> None: ...
```

→ Aggregate/Entity/Value Object 설계 상세는 [tactical-ddd.md](tactical-ddd.md) 참조.

---

## Application 레이어 — `application/`

유스케이스 **조율자**. 비즈니스 로직을 직접 수행하지 않고 Aggregate에 위임한다. `command/`(쓰기)와 `query/`(읽기)로 분리되어 있다.

```python
# application/command/deposit_handler.py
class DepositHandler:
    def __init__(self, repo: AccountRepository, notification_service: NotificationService) -> None:
        self._repo = repo
        self._notification_service = notification_service

    async def execute(self, cmd: DepositCommand) -> Transaction:
        account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)   # 비즈니스 로직은 Aggregate에 위임
        await self._repo.save(account)
        for event in account.pull_events():
            await self._notification_service.notify(event)
        return transaction
```

Handler 생성자는 구체 클래스가 아니라 ABC(`AccountRepository`, `NotificationService`)를 타입으로 받는다. 이 덕분에 [testing.md](testing.md)에서 다루는 Application 단위 테스트가 실제 DB/SES 없이 mock만으로 가능하다.

→ Command/Query Handler 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참조.

### Technical Service 인터페이스 — `application/service/`

기술적 구현이 핵심인 관심사(이메일 발송, 파일 스토리지, Secrets Manager 등)는 Application에 ABC를 두고 Infrastructure에서 구현한다. 이 저장소에서 이미 적용된 예:

```python
# application/service/notification_service.py — 인터페이스
class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — 구현체 (SES)
class SesNotificationService(NotificationService):
    async def notify(self, event: AccountDomainEvent) -> None:
        try:
            await self._send_and_record(event)
        except Exception:
            logger.exception("알림 이메일 발송 실패: ...")
```

**이 분리의 이유:**
- `DepositHandler`가 `aioboto3`나 SES API에 직접 의존하지 않는다.
- 발송 채널이 SES → SNS/Slack 등으로 바뀌어도 구현체만 교체하면 된다.
- 테스트 시 `NotificationService`를 mock 객체로 대체해 실제 이메일 발송 없이 Handler 로직만 검증할 수 있다.

두 번째 기술 관심사(파일 스토리지, Secrets Manager)를 추가할 때도 같은 구조(`application/service/<concern>_service.py` ABC + `infrastructure/<concern>/<provider>_<concern>_service.py` 구현체)를 따른다 — [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md) 참조.

---

## Infrastructure 레이어 — `infrastructure/`

외부 시스템에 실제로 접근하는 유일한 레이어.

```python
# infrastructure/persistence/account_repository.py
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_by_id(self, account_id: str, owner_id: str) -> Account | None:
        stmt = select(AccountModel).where(
            AccountModel.id == account_id,
            AccountModel.owner_id == owner_id,
            AccountModel.deleted_at.is_(None),
        )
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        return self._to_domain(row) if row else None
```

Domain/Application의 ABC 구현체(`SqlAlchemyAccountRepository`, `SesNotificationService`)가 모두 여기 있다. FastAPI에는 전용 DI 컨테이너가 없으므로, `interface/rest/account_router.py`의 `Depends` 팩토리 함수가 "바인딩 지점" 역할을 한다:

```python
# interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)   # AccountRepository(ABC) ← SqlAlchemyAccountRepository(구현체)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)        # NotificationService(ABC) ← SesNotificationService(구현체)
```

라우트 함수의 파라미터 타입은 ABC(`AccountRepository`, `NotificationService`)로 선언되지만, 실제로 주입되는 것은 팩토리가 반환하는 구현체다.

---

## Interface 레이어 — `interface/rest/`

외부 요청(HTTP)의 진입점.

1. 요청 수신 (`account_router.py`)
2. Handler 생성 및 `execute()` 호출
3. 에러는 `main.py`의 `@app.exception_handler`가 캐치하여 HTTP 응답으로 변환 (Router 자체는 캐치하지 않는다)

```python
# interface/rest/account_router.py
@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    notification_service: NotificationService = Depends(_notification_service),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, notification_service).execute(
        DepositCommand(account_id=account_id, requester_id=x_user_id, amount=body.amount)
    )
    return TransactionResponse(...)
```

### Interface DTO는 얇은 변환만

`interface/rest/schemas.py`의 Pydantic 모델(`DepositRequest`, `TransactionResponse`)은 HTTP 요청/응답 형태만 정의하고, 검증 이상의 로직을 갖지 않는다. Application의 Command/Result를 그대로 감싸는 역할이다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object, Domain Event 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Handler 상세
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
- [directory-structure.md](directory-structure.md) — 전체 디렉토리 트리
