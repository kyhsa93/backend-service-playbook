# Repository 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/repository-pattern.md](../../../../docs/architecture/repository-pattern.md)

## Aggregate Root 단위 Repository

Account Aggregate에 대해 구현체는 하나(`SqlAlchemyAccountRepository`)뿐이지만, 인터페이스(ABC)는 쓰기 모델 `AccountRepository`와 읽기 전용 `AccountQuery` 두 개로 나뉜다 — CommandHandler는 `AccountRepository`, QueryHandler는 `AccountQuery`에 의존한다. 자세한 배경은 [cqrs-pattern.md](cqrs-pattern.md) 참조.

```
src/account/
  domain/
    repository.py             ← AccountQuery(ABC, 읽기 전용) + AccountRepository(ABC, AccountQuery 상속 + 쓰기) — 인터페이스
  infrastructure/
    persistence/
      account_repository.py   ← SqlAlchemyAccountRepository(AccountRepository) — 구현체, AccountQuery도 함께 만족
```

```python
# domain/repository.py
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용."""

    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
    """쓰기 모델 — AccountQuery를 상속해 조회 메서드를 재사용한다."""

    @abstractmethod
    async def save_account(self, account: Account) -> None: ...
```

조회 메서드는 `find_accounts` 하나로 통일되어 있다 — 단건 조회는 필터(`account_id`+`owner_id`)와 `take=1`로 호출한 뒤 결과 목록의 첫 항목을 꺼내는 패턴을 쓴다:

```python
# application/command/deposit_handler.py — 단건 조회 패턴
async def execute(self, cmd: DepositCommand) -> Transaction:
    accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
    account = accounts[0] if accounts else None
    if account is None:
        raise AccountNotFoundError(cmd.account_id)
    ...
```

`deposit_handler.py`/`withdraw_handler.py`/`suspend_account_handler.py`/`reactivate_account_handler.py`/`close_account_handler.py`/`get_account_handler.py`/`get_transactions_handler.py` 등 계좌 단건을 조회하는 모든 호출부가 이 패턴을 따른다. java/kotlin-springboot의 `findAccounts` 패턴과 형태가 같다.

CommandHandler는 `AccountRepository` 타입(ABC), QueryHandler는 `AccountQuery` 타입(ABC)으로 주입받는다 — 어느 쪽도 `SqlAlchemyAccountRepository`를 직접 import하지 않는다. DI 바인딩은 FastAPI `Depends` 팩토리가 담당한다: [layer-architecture.md](layer-architecture.md), [cqrs-pattern.md](cqrs-pattern.md) 참조.

`Transaction`(하위 Entity)은 별도 Repository를 갖지 않는다 — `AccountRepository.find_transactions()`를 통해 Account Aggregate 경계 안에서 조회된다. 이는 root의 "Aggregate 내부 하위 Entity는 Aggregate Root의 Repository를 통해 함께 저장/조회한다" 원칙을 정확히 따른다.

---

## Repository 구현체 — `infrastructure/persistence/account_repository.py`

```python
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_account(self, account: Account) -> None:
        existing = await self._session.get(AccountModel, account.account_id)
        if existing:
            existing.amount = account.balance.amount
            existing.status = account.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(AccountModel(id=account.account_id, ...))

        for transaction in account.pull_pending_transactions():
            self._session.add(TransactionModel(...))

        await self._session.flush()
```

`save_account()`가 신규/기존을 판별해 upsert처럼 동작하는 것은 root의 "Repository에 별도 update 메서드를 두지 않는다 — `save<Noun>` 하나로 저장" 원칙과 정확히 일치한다. `pull_pending_transactions()`로 Aggregate가 만든 하위 Entity(`Transaction`)를 함께 저장하는 것도 Aggregate 경계를 지킨 cascade 저장이다.

---

## 동적 필터 패턴

```python
# infrastructure/persistence/account_repository.py
async def find_accounts(self, page: int, take: int, account_id=None, owner_id=None, status=None):
    stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
    count_stmt = select(func.count()).select_from(AccountModel).where(AccountModel.deleted_at.is_(None))

    if account_id:
        stmt = stmt.where(AccountModel.id == account_id)
        count_stmt = count_stmt.where(AccountModel.id == account_id)
    if owner_id:
        stmt = stmt.where(AccountModel.owner_id == owner_id)
        count_stmt = count_stmt.where(AccountModel.owner_id == owner_id)
    if status:
        stmt = stmt.where(AccountModel.status.in_(status))
        count_stmt = count_stmt.where(AccountModel.status.in_(status))
    ...
```

각 조건이 값이 있을 때만 적용되고(`if account_id:`), `count_stmt`도 같은 조건을 병행 적용해 정확한 전체 건수를 계산한다 — [api-response.md](api-response.md)가 요구하는 "count는 필터 적용 후 전체 건수" 원칙과 일치한다.

---

## 원칙

- **1 Aggregate Root = 1 구현체, 쓰기/읽기 ABC는 분리 가능**: `SqlAlchemyAccountRepository` 구현체 하나가 쓰기용 `AccountRepository`와 읽기 전용 `AccountQuery` 두 인터페이스를 함께 만족한다 — [cqrs-pattern.md](cqrs-pattern.md) 참조.
- **인터페이스는 domain/, 구현체는 infrastructure/**.
- **저장은 `save<Noun>` 하나만 사용, 별도 update 메서드 없음**: root의 `save<Noun>` 네이밍을 그대로 따른다 — `save_account`/`save_card`/`save_payment`/`save_refund`. 신규/기존 판별은 메서드 내부에서 upsert로 처리한다.
- **조회 메서드는 `find<Noun>s` 하나로 통일**: `AccountQuery.find_accounts()` 하나로 목록/단건 조회를 모두 처리한다 — 단건 조회는 `account_id`+`owner_id` 필터와 `take=1`로 호출한 뒤 첫 항목을 꺼낸다.
- **동적 필터는 값이 있을 때만 적용**.
- **삭제는 soft delete**: [persistence.md](persistence.md) 참조.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계 상세
- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, DI 바인딩
- [domain-events.md](domain-events.md) — Repository에서 Domain Event → Outbox 저장
- [persistence.md](persistence.md) — 트랜잭션, Soft Delete, 마이그레이션
- [api-response.md](api-response.md) — 목록 조회 응답과 count
